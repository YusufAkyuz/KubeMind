package com.kubemind.k8s.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Raw kubectl terminal, connected directly to the selected cluster — the
 * "just give me a shell" escape hatch for operations the UI doesn't cover.
 *
 * This is a deliberate, accepted trade-off against the rest of the app's
 * security model: every other write path here is allowlisted, validated, and
 * individually audited; a raw shell cannot be. To make the terminal actually
 * useful across any cluster/deployment topology, the session runs as its own
 * ServiceAccount bound to cluster-admin for the lifetime of the connection.
 * Only the session's *existence* is audited (open), not the commands run
 * inside it — that gap is the price of a real shell, and the frontend must
 * say so plainly before connecting. ADMIN-only at the handshake.
 *
 * All three resources (Pod, ServiceAccount, ClusterRoleBinding) are deleted
 * the moment the session ends.
 */
@Component
public class ClusterTerminalWebSocketHandler extends AbstractEphemeralExecHandler {

    private static final String TERMINAL_NAMESPACE = "kube-system";
    private static final String KUBECTL_IMAGE = "alpine/k8s:1.30.0";
    // 60s was too tight for clusters where alpine/k8s isn't already cached on the node
    // (first pull over a slower VM/remote network path, e.g. multipass) — the pod would
    // still be Pending on image pull when waitUntilCondition gave up.
    private static final int READY_TIMEOUT_SECONDS = 180;

    private final ClusterClientFactory clientFactory;
    private final AuditService auditService;

    public ClusterTerminalWebSocketHandler(ClusterClientFactory clientFactory,
                                           AuditService auditService,
                                           ObjectMapper objectMapper,
                                           ExecClusterAccessGuard accessGuard) {
        super(objectMapper, accessGuard);
        this.clientFactory = clientFactory;
        this.auditService = auditService;
    }

    @Override
    protected Provisioned provision(WebSocketSession session, MultiValueMap<String, String> params) throws Exception {
        long clusterId = parseLong(params.getFirst("clusterId"), 0L);
        String username = session.getPrincipal() != null ? session.getPrincipal().getName() : "unknown";
        String ref = "Cluster/" + clusterId;

        var client = clientFactory.getClient(clusterId);
        String suffix = randomSuffix();
        String name = "kubemind-term-" + suffix;

        // The purpose label is what EphemeralSessionReaper selects on. It is
        // deliberately more specific than managed-by=kubemind: a sweep that
        // deletes things needs a selector that cannot match anything else.
        var serviceAccount = new ServiceAccountBuilder()
            .withNewMetadata().withName(name).withNamespace(TERMINAL_NAMESPACE)
                .addToLabels("app.kubernetes.io/managed-by", "kubemind")
                .addToLabels(EphemeralSessionReaper.PURPOSE_LABEL, EphemeralSessionReaper.PURPOSE_CLUSTER_TERMINAL)
            .endMetadata()
            .build();

        var binding = new ClusterRoleBindingBuilder()
            .withNewMetadata().withName(name)
                .addToLabels("app.kubernetes.io/managed-by", "kubemind")
                .addToLabels(EphemeralSessionReaper.PURPOSE_LABEL, EphemeralSessionReaper.PURPOSE_CLUSTER_TERMINAL)
            .endMetadata()
            .withNewRoleRef().withApiGroup("rbac.authorization.k8s.io").withKind("ClusterRole").withName("cluster-admin").endRoleRef()
            .addNewSubject().withKind("ServiceAccount").withName(name).withNamespace(TERMINAL_NAMESPACE).endSubject()
            .build();

        var pod = new PodBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(TERMINAL_NAMESPACE)
                .addToLabels("app.kubernetes.io/managed-by", "kubemind")
                .addToLabels(EphemeralSessionReaper.PURPOSE_LABEL, EphemeralSessionReaper.PURPOSE_CLUSTER_TERMINAL)
            .endMetadata()
            .withNewSpec()
                .withServiceAccountName(name)
                .withRestartPolicy("Never")
                .addNewContainer()
                    .withName("kubectl")
                    .withImage(KUBECTL_IMAGE)
                    .withCommand("sleep", "infinity")
                .endContainer()
            .endSpec()
            .build();

        Runnable cleanup = () -> {
            try { client.pods().inNamespace(TERMINAL_NAMESPACE).withName(name).withGracePeriod(0).delete(); } catch (Exception ignored) {}
            try { client.rbac().clusterRoleBindings().withName(name).delete(); } catch (Exception ignored) {}
            try { client.serviceAccounts().inNamespace(TERMINAL_NAMESPACE).withName(name).delete(); } catch (Exception ignored) {}
        };

        try {
            client.serviceAccounts().inNamespace(TERMINAL_NAMESPACE).resource(serviceAccount).create();
            client.rbac().clusterRoleBindings().resource(binding).create();
            client.pods().inNamespace(TERMINAL_NAMESPACE).resource(pod).create();
            client.pods().inNamespace(TERMINAL_NAMESPACE).withName(name)
                .waitUntilCondition(p -> p != null && p.getStatus() != null
                    && "Running".equals(p.getStatus().getPhase()), READY_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            auditService.record(username, clusterId, "OPEN_CLUSTER_TERMINAL", ref,
                Map.of("grant", "cluster-admin", "sessionServiceAccount", name), true, null);

            return new Provisioned(client, TERMINAL_NAMESPACE, name, "kubectl",
                new String[] {"/bin/bash"}, cleanup);
        } catch (Exception e) {
            cleanup.run();
            auditService.record(username, clusterId, "OPEN_CLUSTER_TERMINAL", ref, null, false, e.getMessage());
            throw e;
        }
    }
}
