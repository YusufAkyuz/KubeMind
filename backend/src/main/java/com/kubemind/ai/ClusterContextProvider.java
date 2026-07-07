package com.kubemind.ai;

import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Builds a compact, redacted snapshot of a cluster for the chat assistant so it
 * can answer questions like "what's broken?" or "how many services do I have?"
 * grounded in real state. Bounded in size on purpose — this goes into every
 * chat prompt. Extend the sections here as more "how many X" questions surface.
 */
@Component
public class ClusterContextProvider {

    private static final int MAX_NAMESPACES = 60;
    private static final int MAX_UNHEALTHY_PODS = 40;
    private static final int MAX_SERVICES = 60;
    private static final int MAX_DEPLOYMENTS = 60;

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

            // Fetch all pods once; used for both per-namespace counts and the unhealthy list.
            List<Pod> pods = client.pods().inAnyNamespace().list().getItems();
            Map<String, Long> podsPerNs = pods.stream().collect(Collectors.groupingBy(
                p -> p.getMetadata().getNamespace(), TreeMap::new, Collectors.counting()));

            sb.append("\n=== NAMESPACES (name: pod count) ===\n");
            var namespaces = client.namespaces().list().getItems();
            namespaces.stream().limit(MAX_NAMESPACES).forEach(ns -> {
                String name = ns.getMetadata().getName();
                sb.append("- ").append(name).append(": ")
                  .append(podsPerNs.getOrDefault(name, 0L)).append(" pods\n");
            });
            if (namespaces.size() > MAX_NAMESPACES) {
                sb.append("… and ").append(namespaces.size() - MAX_NAMESPACES).append(" more\n");
            }

            sb.append("\n=== UNHEALTHY PODS (not Running/Succeeded) ===\n");
            List<Pod> unhealthy = pods.stream()
                .filter(p -> {
                    String phase = p.getStatus() != null ? p.getStatus().getPhase() : null;
                    return !"Running".equals(phase) && !"Succeeded".equals(phase);
                })
                .limit(MAX_UNHEALTHY_PODS)
                .toList();
            if (unhealthy.isEmpty()) {
                sb.append("(none)\n");
            } else {
                unhealthy.forEach(p -> {
                    String phase = p.getStatus() != null ? p.getStatus().getPhase() : "Unknown";
                    sb.append("- ").append(p.getMetadata().getNamespace()).append('/')
                      .append(p.getMetadata().getName()).append(": ").append(phase).append('\n');
                });
            }

            List<Service> services = client.services().inAnyNamespace().list().getItems();
            sb.append("\n=== SERVICES (total: ").append(services.size()).append(") ===\n");
            services.stream().limit(MAX_SERVICES).forEach(s ->
                sb.append("- ").append(s.getMetadata().getNamespace()).append('/')
                  .append(s.getMetadata().getName()).append(": ")
                  .append(s.getSpec() != null ? s.getSpec().getType() : "Unknown").append('\n'));
            if (services.size() > MAX_SERVICES) {
                sb.append("… and ").append(services.size() - MAX_SERVICES).append(" more\n");
            }

            List<Deployment> deployments = client.apps().deployments().inAnyNamespace().list().getItems();
            sb.append("\n=== DEPLOYMENTS (total: ").append(deployments.size()).append(") ===\n");
            deployments.stream().limit(MAX_DEPLOYMENTS).forEach(d -> {
                var status = d.getStatus();
                int ready = status != null && status.getReadyReplicas() != null ? status.getReadyReplicas() : 0;
                int desired = d.getSpec() != null && d.getSpec().getReplicas() != null ? d.getSpec().getReplicas() : 0;
                sb.append("- ").append(d.getMetadata().getNamespace()).append('/')
                  .append(d.getMetadata().getName()).append(": ").append(ready).append('/').append(desired)
                  .append(" ready\n");
            });
            if (deployments.size() > MAX_DEPLOYMENTS) {
                sb.append("… and ").append(deployments.size() - MAX_DEPLOYMENTS).append(" more\n");
            }

        } catch (Exception e) {
            sb.append("\n(cluster context unavailable: ").append(e.getMessage()).append(")\n");
        }
        // Defense in depth: nothing here should be secret, but scrub anyway.
        return Redactor.redactText(sb.toString());
    }
}
