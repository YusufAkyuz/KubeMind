package com.kubemind.ai;

import com.kubemind.cluster.ClusterClientFactory;
import com.kubemind.k8s.MetricsService;
import com.kubemind.k8s.NodeMetricsDto;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Builds a compact, plain-text snapshot of a pod for the AI prompt:
 * spec summary, container states, conditions, owner chain, node health, recent
 * events, and the last N log lines. Everything passes through {@link Redactor}
 * — Secret data is never collected at all (env values from secretKeyRef are
 * masked without ever reading the Secret).
 */
@Component
public class PodContextCollector {

    private static final int LOG_TAIL_LINES = 100;
    private static final int MAX_EVENTS = 20;

    private final ClusterClientFactory clientFactory;
    private final MetricsService metricsService;

    public PodContextCollector(ClusterClientFactory clientFactory, MetricsService metricsService) {
        this.clientFactory = clientFactory;
        this.metricsService = metricsService;
    }

    /** @return the redacted context, or null if the pod does not exist. */
    public String collect(long clusterId, String namespace, String podName) {
        KubernetesClient client = clientFactory.getClient(clusterId);
        Pod pod = client.pods().inNamespace(namespace).withName(podName).get();
        if (pod == null) return null;

        StringBuilder sb = new StringBuilder(8_192);

        // ── Pod summary ─────────────────────────────────────────────────────
        sb.append("=== POD ===\n");
        sb.append("name: ").append(pod.getMetadata().getName()).append('\n');
        sb.append("namespace: ").append(namespace).append('\n');
        var status = pod.getStatus();
        sb.append("phase: ").append(status != null ? status.getPhase() : "Unknown").append('\n');
        if (pod.getMetadata().getDeletionTimestamp() != null) {
            sb.append("deletionTimestamp: ").append(pod.getMetadata().getDeletionTimestamp()).append('\n');
        }
        if (pod.getSpec() != null) {
            sb.append("node: ").append(pod.getSpec().getNodeName()).append('\n');
            sb.append("restartPolicy: ").append(pod.getSpec().getRestartPolicy()).append('\n');
        }

        // ── Owner chain (is this the pod's problem, or its controller's?) ──
        String ownerChain = ownerChain(client, pod, namespace);
        if (ownerChain != null) {
            sb.append("ownerChain: ").append(ownerChain).append('\n');
        }

        // ── Node health (is this a node problem, not a pod problem?) ───────
        if (pod.getSpec() != null && pod.getSpec().getNodeName() != null) {
            appendNodeHealth(sb, client, pod.getSpec().getNodeName());
        }

        // ── Conditions ──────────────────────────────────────────────────────
        if (status != null && status.getConditions() != null && !status.getConditions().isEmpty()) {
            sb.append("\n=== CONDITIONS ===\n");
            status.getConditions().forEach(c -> {
                sb.append(c.getType()).append('=').append(c.getStatus());
                if (c.getReason() != null) sb.append(" reason=").append(c.getReason());
                if (c.getMessage() != null) sb.append(" message=").append(Redactor.redactText(c.getMessage()));
                sb.append('\n');
            });
        }

        // ── Containers (spec + status) ──────────────────────────────────────
        sb.append("\n=== CONTAINERS ===\n");
        List<ContainerStatus> containerStatuses = status != null && status.getContainerStatuses() != null
            ? status.getContainerStatuses() : List.of();

        if (pod.getSpec() != null) {
            for (Container c : pod.getSpec().getContainers()) {
                sb.append("- name: ").append(c.getName()).append('\n');
                sb.append("  image: ").append(c.getImage()).append('\n');

                if (c.getEnv() != null && !c.getEnv().isEmpty()) {
                    sb.append("  env:\n");
                    c.getEnv().forEach(e -> {
                        // Values sourced from Secrets are masked without ever being read.
                        String value = e.getValueFrom() != null && e.getValueFrom().getSecretKeyRef() != null
                            ? Redactor.MASK
                            : Redactor.redactEnvValue(e.getName(), e.getValue());
                        sb.append("    ").append(e.getName()).append('=').append(value).append('\n');
                    });
                }

                containerStatuses.stream()
                    .filter(cs -> c.getName().equals(cs.getName()))
                    .findFirst()
                    .ifPresent(cs -> appendContainerState(sb, cs));
            }
        }

        // ── Recent events ───────────────────────────────────────────────────
        List<Event> events = client.resources(Event.class).inNamespace(namespace).list().getItems().stream()
            .filter(e -> e.getInvolvedObject() != null
                && podName.equals(e.getInvolvedObject().getName())
                && "Pod".equals(e.getInvolvedObject().getKind()))
            .sorted((a, b) -> nullSafe(b.getLastTimestamp()).compareTo(nullSafe(a.getLastTimestamp())))
            .limit(MAX_EVENTS)
            .toList();

        if (!events.isEmpty()) {
            sb.append("\n=== RECENT EVENTS ===\n");
            events.forEach(e -> sb.append('[').append(e.getType()).append("] ")
                .append(e.getReason()).append(": ")
                .append(Redactor.redactText(e.getMessage()))
                .append(" (x").append(e.getCount() != null ? e.getCount() : 1).append(")\n"));
        }

        // ── Logs (last N lines per container, redacted) ─────────────────────
        if (pod.getSpec() != null) {
            for (Container c : pod.getSpec().getContainers()) {
                sb.append("\n=== LOGS (").append(c.getName())
                  .append(", last ").append(LOG_TAIL_LINES).append(" lines) ===\n");
                try {
                    String log = client.pods().inNamespace(namespace).withName(podName)
                        .inContainer(c.getName())
                        .tailingLines(LOG_TAIL_LINES)
                        .getLog();
                    sb.append(log == null || log.isBlank()
                        ? "(no output)\n"
                        : Redactor.redactText(log)).append('\n');
                } catch (Exception e) {
                    // e.g. container is waiting / never started — that itself is a useful signal
                    sb.append("(logs unavailable: ").append(Redactor.redactText(e.getMessage())).append(")\n");
                }
            }
        }

        return sb.toString();
    }

    /** One hop up the owner chain — enough to tell "this pod's issue" from "its controller's issue". */
    private String ownerChain(KubernetesClient client, Pod pod, String namespace) {
        List<OwnerReference> owners = pod.getMetadata().getOwnerReferences();
        if (owners == null || owners.isEmpty()) return null;
        OwnerReference owner = owners.get(0);
        StringBuilder chain = new StringBuilder(owner.getKind()).append('/').append(owner.getName());

        if ("ReplicaSet".equals(owner.getKind())) {
            var rs = client.apps().replicaSets().inNamespace(namespace).withName(owner.getName()).get();
            List<OwnerReference> rsOwners = rs != null ? rs.getMetadata().getOwnerReferences() : null;
            if (rsOwners != null && !rsOwners.isEmpty()) {
                OwnerReference rsOwner = rsOwners.get(0);
                chain.append(" <- ").append(rsOwner.getKind()).append('/').append(rsOwner.getName());
            }
        }
        return chain.toString();
    }

    /**
     * Ready/pressure conditions only — these are stable signals about actual node problems.
     * Live CPU/memory usage is deliberately NOT included here: it fluctuates on every call and
     * would bust the {@code ai_diagnoses} state-hash cache for reasons unrelated to the pod's
     * actual problem. See {@link #liveNodeUsage} for that, which is appended to the prompt only.
     */
    private void appendNodeHealth(StringBuilder sb, KubernetesClient client, String nodeName) {
        Node node = client.nodes().withName(nodeName).get();
        if (node == null) return;

        sb.append("\n=== NODE (").append(nodeName).append(") ===\n");
        if (node.getStatus() != null && node.getStatus().getConditions() != null) {
            node.getStatus().getConditions().stream()
                .filter(c -> Set.of("Ready", "MemoryPressure", "DiskPressure", "PIDPressure").contains(c.getType()))
                .forEach(c -> sb.append(c.getType()).append('=').append(c.getStatus()).append(' '));
            sb.append('\n');
        }
    }

    /**
     * Current CPU/memory usage of the pod's node, or null if unavailable — deliberately excluded
     * from {@link #collect} (and therefore from the cache key). Callers append this only to the
     * outgoing prompt, never to anything that gets hashed.
     */
    public String liveNodeUsage(long clusterId, String namespace, String podName) {
        Pod pod = clientFactory.getClient(clusterId).pods().inNamespace(namespace).withName(podName).get();
        String nodeName = pod != null && pod.getSpec() != null ? pod.getSpec().getNodeName() : null;
        if (nodeName == null) return null;

        return metricsService.listNodeMetrics(clusterId).stream()
            .filter(m -> nodeName.equals(m.name()))
            .findFirst()
            .map(m -> "node " + nodeName + " current usage: cpu=" + m.cpuUsage() + " memory=" + m.memoryUsage())
            .orElse(null);
    }

    private void appendContainerState(StringBuilder sb, ContainerStatus cs) {
        sb.append("  ready: ").append(Boolean.TRUE.equals(cs.getReady())).append('\n');
        sb.append("  restartCount: ").append(cs.getRestartCount() != null ? cs.getRestartCount() : 0).append('\n');
        var state = cs.getState();
        if (state != null) {
            if (state.getWaiting() != null) {
                sb.append("  state: Waiting reason=").append(state.getWaiting().getReason());
                if (state.getWaiting().getMessage() != null) {
                    sb.append(" message=").append(Redactor.redactText(state.getWaiting().getMessage()));
                }
                sb.append('\n');
            } else if (state.getTerminated() != null) {
                sb.append("  state: Terminated reason=").append(state.getTerminated().getReason())
                  .append(" exitCode=").append(state.getTerminated().getExitCode()).append('\n');
            } else if (state.getRunning() != null) {
                sb.append("  state: Running since=").append(state.getRunning().getStartedAt()).append('\n');
            }
        }
        var last = cs.getLastState();
        if (last != null && last.getTerminated() != null) {
            sb.append("  lastState: Terminated reason=").append(last.getTerminated().getReason())
              .append(" exitCode=").append(last.getTerminated().getExitCode()).append('\n');
        }
    }

    private String nullSafe(String s) {
        return s != null ? s : "";
    }
}
