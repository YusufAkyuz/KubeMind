package com.kubemind.ai;

import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

/**
 * Builds a compact, redacted snapshot of a cluster for the chat assistant so it
 * can answer questions like "what's broken?" grounded in real state. Bounded in
 * size on purpose — this goes into every chat prompt.
 */
@Component
public class ClusterContextProvider {

    private static final int MAX_NAMESPACES = 60;
    private static final int MAX_UNHEALTHY_PODS = 40;

    private final ClusterClientFactory clientFactory;

    public ClusterContextProvider(ClusterClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    public String summarize(long clusterId) {
        StringBuilder sb = new StringBuilder(2_048);
        try {
            KubernetesClient client = clientFactory.getClient(clusterId);

            sb.append("=== NODES ===\n");
            client.nodes().list().getItems().forEach(n -> {
                String ready = n.getStatus() != null && n.getStatus().getConditions() != null
                    ? n.getStatus().getConditions().stream()
                        .filter(c -> "Ready".equals(c.getType())).findFirst()
                        .map(c -> "True".equals(c.getStatus()) ? "Ready" : "NotReady").orElse("Unknown")
                    : "Unknown";
                sb.append("- ").append(n.getMetadata().getName()).append(": ").append(ready).append('\n');
            });

            sb.append("\n=== NAMESPACES ===\n");
            var namespaces = client.namespaces().list().getItems();
            namespaces.stream().limit(MAX_NAMESPACES)
                .forEach(ns -> sb.append("- ").append(ns.getMetadata().getName()).append('\n'));
            if (namespaces.size() > MAX_NAMESPACES) {
                sb.append("… and ").append(namespaces.size() - MAX_NAMESPACES).append(" more\n");
            }

            sb.append("\n=== UNHEALTHY PODS (not Running/Succeeded) ===\n");
            long[] shown = {0};
            boolean[] any = {false};
            client.pods().inAnyNamespace().list().getItems().stream()
                .filter(p -> {
                    String phase = p.getStatus() != null ? p.getStatus().getPhase() : null;
                    return !"Running".equals(phase) && !"Succeeded".equals(phase);
                })
                .limit(MAX_UNHEALTHY_PODS)
                .forEach(p -> {
                    any[0] = true;
                    shown[0]++;
                    String phase = p.getStatus() != null ? p.getStatus().getPhase() : "Unknown";
                    sb.append("- ").append(p.getMetadata().getNamespace()).append('/')
                      .append(p.getMetadata().getName()).append(": ").append(phase).append('\n');
                });
            if (!any[0]) sb.append("(none)\n");

        } catch (Exception e) {
            sb.append("\n(cluster context unavailable: ").append(e.getMessage()).append(")\n");
        }
        // Defense in depth: nothing here should be secret, but scrub anyway.
        return Redactor.redactText(sb.toString());
    }
}
