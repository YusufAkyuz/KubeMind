package com.kubemind.k8s;

import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

/**
 * Generic "view YAML / edit YAML" for any resource kind this app manages,
 * except Pod — a running pod's spec is almost entirely immutable in
 * Kubernetes, so editing one is not a useful operation (delete + recreate,
 * or edit the owning Deployment/StatefulSet instead).
 */
@Service
public class ResourceEditService {

    public static final Set<String> EDITABLE_KINDS = ResourceCreationService.ALLOWED_KINDS.stream()
        .filter(k -> !k.equals("Pod"))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** Cluster-scoped kinds editable through the no-namespace route. */
    public static final Set<String> CLUSTER_SCOPED_EDITABLE_KINDS =
        Set.of("Namespace", "ClusterRole", "ClusterRoleBinding");

    private final ClusterClientFactory clientFactory;
    private final AuditService auditService;

    public ResourceEditService(ClusterClientFactory clientFactory, AuditService auditService) {
        this.clientFactory = clientFactory;
        this.auditService = auditService;
    }

    /**
     * Fetches the live object generically, or null if it doesn't exist. Used by both
     * the YAML view and the AI context collector — null lets callers decide 404 vs
     * "skip this in the AI context" without a control-flow exception either way.
     */
    public HasMetadata fetch(long clusterId, String kind, String ns, String name) {
        requireEditableKind(kind);
        String lookup = ManifestValidation.buildLookupManifest(kind, ns, name);
        return clientFactory.getClient(clusterId).resource(lookup).inNamespace(ns).get();
    }

    public String getYaml(long clusterId, String kind, String ns, String name) {
        HasMetadata resource = fetch(clusterId, kind, ns, name);
        if (resource == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, kind + "/" + ns + "/" + name + " not found");
        }
        // managedFields is server bookkeeping — pure noise in an editor.
        resource.getMetadata().setManagedFields(null);
        return Serialization.asYaml(resource);
    }

    public ResourceCreationService.CreatedResourceDto applyYaml(String username, long clusterId,
                                                                String kind, String ns, String name, String yaml) {
        requireEditableKind(kind);
        String ref = kind + "/" + ns + "/" + name;
        Map<String, Object> payload = Map.of("kind", kind, "yamlBytes", yaml != null ? yaml.length() : 0);

        try {
            var parsed = ManifestValidation.parseAndValidate(yaml, ns, EDITABLE_KINDS);
            if (!kind.equals(parsed.kind())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "kind must remain '" + kind + "'");
            }
            if (!name.equals(parsed.name())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "metadata.name must remain '" + name + "'");
            }

            HasMetadata updated = clientFactory.getClient(clusterId).resource(yaml).inNamespace(ns).update();
            auditService.record(username, clusterId, "EDIT_RESOURCE_YAML", ref, payload, true, null);
            return new ResourceCreationService.CreatedResourceDto(kind, name, ns);
        } catch (Exception e) {
            auditService.record(username, clusterId, "EDIT_RESOURCE_YAML", ref, payload, false, e.getMessage());
            throw e;
        }
    }

    public void delete(String username, long clusterId, String kind, String ns, String name) {
        requireEditableKind(kind);
        String ref = kind + "/" + ns + "/" + name;
        try {
            String lookup = ManifestValidation.buildLookupManifest(kind, ns, name);
            var client = clientFactory.getClient(clusterId);
            if (client.resource(lookup).inNamespace(ns).get() == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ref + " not found");
            }
            client.resource(lookup).inNamespace(ns).delete();
            auditService.record(username, clusterId, "DELETE_RESOURCE", ref, Map.of("kind", kind), true, null);
        } catch (Exception e) {
            auditService.record(username, clusterId, "DELETE_RESOURCE", ref, Map.of("kind", kind), false, e.getMessage());
            throw e;
        }
    }

    private void requireEditableKind(String kind) {
        if (!EDITABLE_KINDS.contains(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Kind '" + kind + "' is not supported here. Supported kinds: "
                + String.join(", ", EDITABLE_KINDS.stream().sorted().toList()));
        }
    }

    // ── Cluster-scoped (Namespace) ──────────────────────────────────────────────

    public String getYamlClusterScoped(long clusterId, String kind, String name) {
        requireClusterScopedKind(kind);
        String lookup = ManifestValidation.buildClusterScopedLookupManifest(kind, name);
        HasMetadata resource = clientFactory.getClient(clusterId).resource(lookup).get();
        if (resource == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, kind + "/" + name + " not found");
        }
        resource.getMetadata().setManagedFields(null);
        return Serialization.asYaml(resource);
    }

    public ResourceCreationService.CreatedResourceDto applyYamlClusterScoped(String username, long clusterId,
                                                                             String kind, String name, String yaml) {
        requireClusterScopedKind(kind);
        String ref = kind + "/" + name;
        Map<String, Object> payload = Map.of("kind", kind, "yamlBytes", yaml != null ? yaml.length() : 0);

        try {
            var parsed = ManifestValidation.parseAndValidateClusterScoped(yaml, CLUSTER_SCOPED_EDITABLE_KINDS);
            if (!kind.equals(parsed.kind())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kind must remain '" + kind + "'");
            }
            if (!name.equals(parsed.name())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "metadata.name must remain '" + name + "'");
            }

            clientFactory.getClient(clusterId).resource(yaml).update();
            auditService.record(username, clusterId, "EDIT_RESOURCE_YAML", ref, payload, true, null);
            return new ResourceCreationService.CreatedResourceDto(kind, name, null);
        } catch (Exception e) {
            auditService.record(username, clusterId, "EDIT_RESOURCE_YAML", ref, payload, false, e.getMessage());
            throw e;
        }
    }

    private void requireClusterScopedKind(String kind) {
        if (!CLUSTER_SCOPED_EDITABLE_KINDS.contains(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Kind '" + kind + "' is not supported here. Supported kinds: "
                + String.join(", ", CLUSTER_SCOPED_EDITABLE_KINDS.stream().sorted().toList()));
        }
    }
}
