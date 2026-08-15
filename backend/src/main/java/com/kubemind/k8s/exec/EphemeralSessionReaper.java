package com.kubemind.k8s.exec;

import com.kubemind.cluster.Cluster;
import com.kubemind.cluster.ClusterClientFactory;
import com.kubemind.cluster.ClusterRepository;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Deletes terminal-session leftovers that outlived their session.
 *
 * The Cluster Terminal and Node Shell each provision throwaway resources and
 * remove them when the WebSocket closes — but only if this process is alive to
 * notice. Kill the backend mid-session (a crash, a redeploy, a pod eviction,
 * Ctrl-C in development) and nothing ever runs that teardown. What survives is
 * not inert: the Cluster Terminal's ServiceAccount stays bound to cluster-admin,
 * and the Node Shell's debug pod keeps running privileged with the host
 * filesystem mounted. One such ServiceAccount was found alive 26 days after its
 * session ended.
 *
 * A session lasts hours at most, so anything older than {@link #MAX_SESSION_AGE}
 * cannot belong to a live one. Age rather than a sweep-on-startup because the
 * chart supports more than one replica, and a starting replica must not tear
 * down a session another replica is currently serving.
 */
@Component
public class EphemeralSessionReaper {

    public static final String PURPOSE_LABEL = "kubemind.io/purpose";
    public static final String PURPOSE_CLUSTER_TERMINAL = "cluster-terminal";
    public static final String PURPOSE_NODE_DEBUG = "node-debug";

    /** Generous: a long debugging session is still far under this. */
    static final Duration MAX_SESSION_AGE = Duration.ofHours(24);

    private static final Duration INTERVAL = Duration.ofHours(1);
    private static final String TERMINAL_NAMESPACE = "kube-system";

    private static final Logger log = LoggerFactory.getLogger(EphemeralSessionReaper.class);

    private final ClusterClientFactory clientFactory;
    private final ClusterRepository clusterRepository;

    public EphemeralSessionReaper(ClusterClientFactory clientFactory, ClusterRepository clusterRepository) {
        this.clientFactory = clientFactory;
        this.clusterRepository = clusterRepository;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
    public void reapAll() {
        List<Long> clusterIds = new ArrayList<>();
        clusterIds.add(ClusterClientFactory.DEFAULT_CLUSTER_ID);
        clusterRepository.findAll().stream()
            .filter(c -> "APPROVED".equals(c.getStatus()))
            .map(Cluster::getId)
            .forEach(clusterIds::add);

        for (Long clusterId : clusterIds) {
            try {
                reap(clusterId);
            } catch (Exception e) {
                // One unreachable cluster must not stop the others — and a failure
                // here is never fatal, the next run tries again.
                log.debug("Session reap skipped for cluster {}: {}", clusterId, e.getMessage());
            }
        }
    }

    int reap(long clusterId) {
        return reap(clusterId, Instant.now().minus(MAX_SESSION_AGE));
    }

    /**
     * Each kind is swept independently: in restricted mode the RBAC sweeps get a
     * 403, and that must not stop the pod sweep — the abandoned Node Shell pod is
     * privileged and host-mounted, so it is the one thing most worth removing.
     *
     * @param cutoff anything created before this is considered abandoned. A
     *               parameter so tests can move the boundary instead of the
     *               clock — the Kubernetes mock server stamps its own
     *               creationTimestamp and ignores whatever a test sets.
     * @return how many objects were deleted, for tests and logging.
     */
    int reap(long clusterId, Instant cutoff) {
        KubernetesClient client = clientFactory.getSystemClient(clusterId);
        int deleted = 0;

        // Two equality selectors rather than one set-based withLabelIn: this code
        // deletes things, so it uses the narrowest, most widely supported query
        // available. (The Kubernetes mock server does not implement set-based
        // selectors at all and returns everything, which would have made a green
        // test suite hide a sweep that deleted every pod in the cluster.)
        for (String purpose : new String[]{PURPOSE_CLUSTER_TERMINAL, PURPOSE_NODE_DEBUG}) {
            deleted += sweep("pods/" + purpose, () -> {
                int n = 0;
                for (var pod : client.pods().inAnyNamespace()
                        .withLabel(PURPOSE_LABEL, purpose).list().getItems()) {
                    if (hasPurpose(pod, purpose) && isOlderThan(pod, cutoff)) {
                        log.warn("Reaping abandoned {} pod {}/{} (created {})", purpose,
                            pod.getMetadata().getNamespace(), pod.getMetadata().getName(),
                            pod.getMetadata().getCreationTimestamp());
                        client.pods().inNamespace(pod.getMetadata().getNamespace())
                            .withName(pod.getMetadata().getName()).withGracePeriod(0).delete();
                        n++;
                    }
                }
                return n;
            });
        }

        deleted += sweep("clusterrolebindings", () -> {
            int n = 0;
            for (var binding : client.rbac().clusterRoleBindings()
                    .withLabel(PURPOSE_LABEL, PURPOSE_CLUSTER_TERMINAL).list().getItems()) {
                if (hasPurpose(binding, PURPOSE_CLUSTER_TERMINAL) && isOlderThan(binding, cutoff)) {
                    log.warn("Reaping abandoned cluster-terminal ClusterRoleBinding {} (created {})",
                        binding.getMetadata().getName(), binding.getMetadata().getCreationTimestamp());
                    client.rbac().clusterRoleBindings().withName(binding.getMetadata().getName()).delete();
                    n++;
                }
            }
            return n;
        });

        deleted += sweep("serviceaccounts", () -> {
            int n = 0;
            for (var sa : client.serviceAccounts().inNamespace(TERMINAL_NAMESPACE)
                    .withLabel(PURPOSE_LABEL, PURPOSE_CLUSTER_TERMINAL).list().getItems()) {
                if (hasPurpose(sa, PURPOSE_CLUSTER_TERMINAL) && isOlderThan(sa, cutoff)) {
                    log.warn("Reaping abandoned cluster-terminal ServiceAccount {}/{} (created {})",
                        TERMINAL_NAMESPACE, sa.getMetadata().getName(), sa.getMetadata().getCreationTimestamp());
                    client.serviceAccounts().inNamespace(TERMINAL_NAMESPACE)
                        .withName(sa.getMetadata().getName()).delete();
                    n++;
                }
            }
            return n;
        });

        if (deleted > 0) {
            log.warn("Reaped {} abandoned terminal-session object(s) on cluster {}. These outlive "
                + "their session when the backend stops before the WebSocket closes.", deleted, clusterId);
        }
        return deleted;
    }

    private int sweep(String what, java.util.function.IntSupplier body) {
        try {
            return body.getAsInt();
        } catch (Exception e) {
            log.debug("Session reap of {} skipped: {}", what, e.getMessage());
            return 0;
        }
    }

    /**
     * Re-checks the label on the object we got back. The server-side selector is
     * the query; this is the guard. Deleting is irreversible, and a selector that
     * silently fails to filter would otherwise hand this method the whole cluster.
     */
    static boolean hasPurpose(HasMetadata resource, String purpose) {
        var meta = resource.getMetadata();
        return meta != null && meta.getLabels() != null
            && purpose.equals(meta.getLabels().get(PURPOSE_LABEL));
    }

    /**
     * Missing or unparseable timestamps count as NOT expired: refusing to delete
     * something we can't date is the safe direction, and the next run sees it again.
     */
    static boolean isOlderThan(HasMetadata resource, Instant cutoff) {
        String created = resource.getMetadata() != null
            ? resource.getMetadata().getCreationTimestamp() : null;
        if (created == null || created.isBlank()) {
            return false;
        }
        try {
            return Instant.parse(created).isBefore(cutoff);
        } catch (Exception e) {
            return false;
        }
    }
}
