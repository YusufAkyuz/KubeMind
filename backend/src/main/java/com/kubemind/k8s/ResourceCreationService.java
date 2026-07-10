package com.kubemind.k8s;

import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
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
        "Pod", "Deployment", "StatefulSet", "DaemonSet", "Job", "CronJob",
        "Service", "Ingress", "ConfigMap", "Secret", "PersistentVolumeClaim",
        "HorizontalPodAutoscaler"
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
        var identity = ManifestValidation.extractBestEffort(yaml);
        String ref = identity.kind() + "/" + ns + "/" + identity.name();
        Map<String, Object> payload = Map.of("kind", identity.kind());

        try {
            validateSize(yaml);
            var parsed = ManifestValidation.parseAndValidate(yaml, ns, ALLOWED_KINDS);

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

    private void validateSize(String yaml) {
        if (yaml.getBytes().length > MAX_YAML_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Manifest is too large");
        }
    }
}
