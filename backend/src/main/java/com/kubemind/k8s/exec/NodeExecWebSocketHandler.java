package com.kubemind.k8s.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.PodBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Node shell: replicates `kubectl debug node/x` by scheduling a privileged,
 * host-mounted debug pod directly onto the target node, then exec'ing into
 * it with `chroot /host` so the user lands in the node's own root filesystem.
 *
 * This is materially more privileged than pod exec — the debug pod can read
 * and modify anything on the host — so the frontend requires an explicit
 * "I understand the risk" confirmation before opening a connection, and the
 * handshake is ADMIN-only (see SecurityConfig). The debug pod is deleted the
 * moment the session ends.
 */
@Component
public class NodeExecWebSocketHandler extends AbstractEphemeralExecHandler {

    private static final String DEBUG_NAMESPACE = "kube-system";
    private static final String DEBUG_IMAGE = "busybox:1.36";
    // 60s was too tight for clusters where busybox isn't already cached on the node
    // (first pull over a slower VM/remote network path, e.g. multipass) — the pod would
    // still be Pending on image pull when waitUntilCondition gave up.
    private static final int READY_TIMEOUT_SECONDS = 180;

    private final ClusterClientFactory clientFactory;
    private final AuditService auditService;

    public NodeExecWebSocketHandler(ClusterClientFactory clientFactory,
                                    AuditService auditService,
                                    ObjectMapper objectMapper) {
        super(objectMapper);
        this.clientFactory = clientFactory;
        this.auditService = auditService;
    }

    @Override
    protected Provisioned provision(WebSocketSession session, MultiValueMap<String, String> params) throws Exception {
        long clusterId = parseLong(params.getFirst("clusterId"), 0L);
        String node = params.getFirst("node");
        String username = session.getPrincipal() != null ? session.getPrincipal().getName() : "unknown";
        String ref = "Node/" + node;

        if (node == null || node.isBlank()) {
            throw new IllegalArgumentException("Missing node parameter");
        }

        var client = clientFactory.getClient(clusterId);
        var existing = client.nodes().withName(node).get();
        if (existing == null) {
            auditService.record(username, clusterId, "NODE_EXEC", ref, null, false, "node not found");
            throw new IllegalArgumentException("Node '" + node + "' not found");
        }

        String podName = "kubemind-debug-" + sanitize(node) + "-" + randomSuffix();

        var pod = new PodBuilder()
            .withNewMetadata()
                .withName(podName)
                .withNamespace(DEBUG_NAMESPACE)
                .addToLabels("app.kubernetes.io/managed-by", "kubemind")
                .addToLabels("kubemind.io/purpose", "node-debug")
            .endMetadata()
            .withNewSpec()
                .withNodeName(node)
                .withHostPID(true)
                .withRestartPolicy("Never")
                // Broad toleration so this schedules on tainted nodes too
                // (e.g. the sole control-plane node of a single-node kind cluster).
                .addNewToleration().withOperator("Exists").endToleration()
                .addNewContainer()
                    .withName("debug")
                    .withImage(DEBUG_IMAGE)
                    .withCommand("sleep", "infinity")
                    .withNewSecurityContext().withPrivileged(true).endSecurityContext()
                    .addNewVolumeMount().withName("host-root").withMountPath("/host").endVolumeMount()
                .endContainer()
                .addNewVolume()
                    .withName("host-root")
                    .withNewHostPath().withPath("/").endHostPath()
                .endVolume()
            .endSpec()
            .build();

        Runnable cleanup = () -> {
            try {
                client.pods().inNamespace(DEBUG_NAMESPACE).withName(podName)
                    .withGracePeriod(0).delete();
            } catch (Exception ignored) {}
        };

        try {
            client.pods().inNamespace(DEBUG_NAMESPACE).resource(pod).create();
            client.pods().inNamespace(DEBUG_NAMESPACE).withName(podName)
                .waitUntilCondition(p -> p != null && p.getStatus() != null
                    && "Running".equals(p.getStatus().getPhase()), READY_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            auditService.record(username, clusterId, "NODE_EXEC", ref,
                Map.of("debugPod", podName), true, null);

            return new Provisioned(client, DEBUG_NAMESPACE, podName, "debug",
                new String[] {"/bin/busybox", "chroot", "/host", "/bin/sh"}, cleanup);
        } catch (Exception e) {
            cleanup.run();
            auditService.record(username, clusterId, "NODE_EXEC", ref, null, false, e.getMessage());
            throw e;
        }
    }

    private String sanitize(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }
}
