package com.kubemind.k8s;

import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creates arbitrary-but-allowlisted Kubernetes resources from a user-authored
 * YAML manifest. Deliberately scoped to the kinds this app already manages
 * elsewhere — RBAC objects, Namespaces, and CRDs are out of scope so this
 * feature cannot be used for privilege escalation (see CLAUDE.md least-privilege
 * rule). Every attempt, successful or not, is audited.
 */
@Service
public class ResourceCreationService {

    /** Kinds a user may create through this feature. Keep in sync with the frontend's KIND_ROUTES. */
    public static final Set<String> ALLOWED_KINDS = Set.of(
        "Pod", "Deployment", "StatefulSet", "DaemonSet",
        "Service", "Ingress", "ConfigMap", "Secret", "PersistentVolumeClaim"
    );

    private static final int MAX_YAML_BYTES = 64 * 1024;

    private final ClusterClientFactory clientFactory;
    private final AuditService auditService;

    public ResourceCreationService(ClusterClientFactory clientFactory, AuditService auditService) {
        this.clientFactory = clientFactory;
        this.auditService = auditService;
    }

    public record CreatedResourceDto(String kind, String name, String namespace) {}

    public List<String> allowedKinds() {
        return ALLOWED_KINDS.stream().sorted().toList();
    }

    public CreatedResourceDto create(String username, long clusterId, String ns, String yaml) {
        // Best-effort identity for the audit entry, even if validation below rejects
        // the manifest — a blocked attempt (e.g. someone submitting a ClusterRoleBinding)
        // is exactly the kind of thing the audit log exists to capture.
        BestEffortIdentity identity = extractBestEffort(yaml);
        String ref = identity.kind() + "/" + ns + "/" + identity.name();
        Map<String, Object> payload = Map.of("kind", identity.kind());

        try {
            validateSize(yaml);
            ParsedManifest parsed = parseAndValidate(ns, yaml);

            // A create is atomic — either the object is persisted or it isn't, so
            // there's no partial state to guard against with a separate dry-run step.
            clientFactory.getClient(clusterId).resource(yaml).inNamespace(ns).create();

            auditService.record(username, clusterId, "CREATE_RESOURCE", ref, payload, true, null);
            return new CreatedResourceDto(parsed.kind(), parsed.name(), ns);
        } catch (Exception e) {
            auditService.record(username, clusterId, "CREATE_RESOURCE", ref, payload, false, e.getMessage());
            throw e;
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private record ParsedManifest(String kind, String name) {}

    private record BestEffortIdentity(String kind, String name) {}

    /** Loose extraction for audit labeling only — never throws, never enforces rules. */
    private BestEffortIdentity extractBestEffort(String yaml) {
        try {
            var parsed = Serialization.unmarshal(yaml, GenericKubernetesResource.class);
            String kind = parsed.getKind() != null ? parsed.getKind() : "unknown";
            String name = parsed.getMetadata() != null && parsed.getMetadata().getName() != null
                ? parsed.getMetadata().getName() : "unknown";
            return new BestEffortIdentity(kind, name);
        } catch (Exception e) {
            return new BestEffortIdentity("unknown", "unknown");
        }
    }

    private ParsedManifest parseAndValidate(String ns, String yaml) {
        GenericKubernetesResource parsed;
        try {
            parsed = Serialization.unmarshal(yaml, GenericKubernetesResource.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid YAML: " + rootMessage(e));
        }

        String kind = parsed.getKind();
        if (kind == null || !ALLOWED_KINDS.contains(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Kind '" + kind + "' is not supported here. Supported kinds: "
                + String.join(", ", allowedKinds()));
        }

        if (parsed.getMetadata() == null || parsed.getMetadata().getName() == null
            || parsed.getMetadata().getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metadata.name is required");
        }

        String parsedNs = parsed.getMetadata().getNamespace();
        if (parsedNs != null && !parsedNs.isBlank() && !parsedNs.equals(ns)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "metadata.namespace ('" + parsedNs + "') must match the target namespace '" + ns
                + "', or be omitted");
        }

        return new ParsedManifest(kind, parsed.getMetadata().getName());
    }

    private void validateSize(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Manifest is empty");
        }
        if (yaml.getBytes().length > MAX_YAML_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Manifest is too large");
        }
    }

    private String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage();
    }
}
