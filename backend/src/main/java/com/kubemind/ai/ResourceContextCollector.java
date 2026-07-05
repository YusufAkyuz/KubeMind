package com.kubemind.ai;

import com.kubemind.cluster.ClusterClientFactory;
import com.kubemind.k8s.ResourceEditService;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds a compact, redacted snapshot of any non-Pod resource kind for the AI
 * prompt (Pods keep their own richer collector with logs — see
 * {@link PodContextCollector}). Secret values are never serialized, even
 * base64-encoded — only key names and the Secret's type are included.
 */
@Component
public class ResourceContextCollector {

    private static final int MAX_EVENTS = 20;

    private final ClusterClientFactory clientFactory;
    private final ResourceEditService resourceEditService;

    public ResourceContextCollector(ClusterClientFactory clientFactory, ResourceEditService resourceEditService) {
        this.clientFactory = clientFactory;
        this.resourceEditService = resourceEditService;
    }

    /** @return the redacted context, or null if the resource does not exist. */
    public String collect(long clusterId, String kind, String namespace, String name) {
        HasMetadata resource = resourceEditService.fetch(clusterId, kind, namespace, name);
        if (resource == null) return null;

        StringBuilder sb = new StringBuilder(4_096);
        sb.append("=== ").append(kind.toUpperCase()).append(" ===\n");

        if ("Secret".equals(kind) && resource instanceof Secret secret) {
            // Never serialize Secret data/stringData, not even base64 — only shape.
            sb.append("name: ").append(name).append('\n');
            sb.append("namespace: ").append(namespace).append('\n');
            sb.append("type: ").append(secret.getType()).append('\n');
            var keys = secret.getData() != null ? secret.getData().keySet() : java.util.Set.<String>of();
            sb.append("keys: ").append(String.join(", ", keys)).append('\n');
            sb.append("(values withheld — Secret data is never sent to the AI)\n");
        } else {
            resource.getMetadata().setManagedFields(null);
            sb.append(Redactor.redactText(Serialization.asYaml(resource))).append('\n');
        }

        List<Event> events = clientFactory.getClient(clusterId).resources(Event.class)
            .inNamespace(namespace).list().getItems().stream()
            .filter(e -> e.getInvolvedObject() != null
                && name.equals(e.getInvolvedObject().getName())
                && kind.equals(e.getInvolvedObject().getKind()))
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

        return sb.toString();
    }

    private String nullSafe(String s) {
        return s != null ? s : "";
    }
}
