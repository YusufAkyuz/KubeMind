package com.kubemind.ai;

import com.kubemind.cluster.ClusterClientFactory;
import com.kubemind.k8s.ResourceEditService;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

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

        if ("Service".equals(kind) && resource instanceof Service service) {
            appendServiceEndpoints(sb, clusterId, namespace, service);
        }
        if ("PersistentVolumeClaim".equals(kind) && resource instanceof PersistentVolumeClaim pvc) {
            appendVolumeInfo(sb, clusterId, pvc);
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

    /** Is this Service actually routing anywhere? A "healthy-looking" Service with zero ready endpoints is a common trap. */
    private void appendServiceEndpoints(StringBuilder sb, long clusterId, String namespace, Service service) {
        Map<String, String> selector = service.getSpec() != null ? service.getSpec().getSelector() : null;
        if (selector == null || selector.isEmpty()) {
            sb.append("\n=== ENDPOINTS ===\nno selector (likely a manually-managed Endpoints object)\n");
            return;
        }
        List<Pod> matching = clientFactory.getClient(clusterId).pods()
            .inNamespace(namespace).withLabels(selector).list().getItems();
        long ready = matching.stream().filter(this::isPodReady).count();

        sb.append("\n=== ENDPOINTS ===\n");
        sb.append("matching pods: ").append(matching.size())
          .append(", ready: ").append(ready).append('\n');
        if (matching.isEmpty()) {
            sb.append("no pods match this Service's selector — traffic has nowhere to go\n");
        }
    }

    private boolean isPodReady(Pod p) {
        return p.getStatus() != null && p.getStatus().getConditions() != null
            && p.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
    }

    /** Is the claim actually bound to storage, and what class/policy governs it? */
    private void appendVolumeInfo(StringBuilder sb, long clusterId, PersistentVolumeClaim pvc) {
        sb.append("\n=== VOLUME ===\n");
        String volumeName = pvc.getSpec() != null ? pvc.getSpec().getVolumeName() : null;
        if (volumeName == null || volumeName.isBlank()) {
            sb.append("not yet bound to a PersistentVolume\n");
            String scName = pvc.getSpec() != null ? pvc.getSpec().getStorageClassName() : null;
            sb.append("storageClassName: ").append(scName != null ? scName : "(default)").append('\n');
            return;
        }
        var pv = clientFactory.getClient(clusterId).persistentVolumes().withName(volumeName).get();
        if (pv == null) {
            sb.append("bound volumeName=").append(volumeName).append(" but the PV no longer exists\n");
            return;
        }
        sb.append("PersistentVolume: ").append(volumeName).append('\n');
        sb.append("phase: ").append(pv.getStatus() != null ? pv.getStatus().getPhase() : "Unknown").append('\n');
        if (pv.getSpec() != null) {
            sb.append("storageClassName: ").append(pv.getSpec().getStorageClassName()).append('\n');
            sb.append("persistentVolumeReclaimPolicy: ").append(pv.getSpec().getPersistentVolumeReclaimPolicy()).append('\n');
        }
    }

    private String nullSafe(String s) {
        return s != null ? s : "";
    }
}
