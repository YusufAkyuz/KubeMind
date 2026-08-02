package com.kubemind.k8s;

import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import com.kubemind.config.PrivilegedFeatures;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creates arbitrary-but-allowlisted Kubernetes resources from a user-authored
 * YAML manifest. Deliberately scoped to the kinds this app already manages
 * elsewhere. Namespaces and CRDs are out of scope so this feature cannot be
 * used for privilege escalation via cluster-scoped admin objects. Every
 * attempt, successful or not, is audited.
 *
 * ServiceAccount/Role/RoleBinding ARE included (2026-07: maintainer explicitly
 * accepted the privilege-escalation risk this reopens — an ADMIN can now grant
 * any permission to any identity via a RoleBinding, same trust tier as the
 * Cluster Terminal's cluster-admin grant. Still ADMIN-only + audited, no new
 * exception beyond what CLAUDE.md already documents for ADMIN-tier actions).
 * ClusterRole/ClusterRoleBinding are cluster-scoped and go through
 * {@link ClusterResourceCreationService} instead — see CLUSTER_ALLOWED_KINDS.
 */
@Service
public class ResourceCreationService {

    /** Kinds a user may create through this feature. Keep in sync with the frontend's KIND_ROUTES. */
    public static final Set<String> ALLOWED_KINDS = Set.of(
        "Pod", "Deployment", "StatefulSet", "DaemonSet", "Job", "CronJob",
        "Service", "Ingress", "ConfigMap", "Secret", "PersistentVolumeClaim",
        "HorizontalPodAutoscaler", "ServiceAccount", "Role", "RoleBinding"
    );

    private static final int MAX_YAML_BYTES = 64 * 1024;

    private final ClusterClientFactory clientFactory;
    private final AuditService auditService;
    private final PrivilegedFeatures privilegedFeatures;

    public ResourceCreationService(ClusterClientFactory clientFactory, AuditService auditService,
                                   PrivilegedFeatures privilegedFeatures) {
        this.clientFactory = clientFactory;
        this.auditService = auditService;
        this.privilegedFeatures = privilegedFeatures;
    }

    public record CreatedResourceDto(String kind, String name, String namespace) {}

    /**
     * What this install actually permits right now. On a deployment running without
     * cluster-admin the RBAC kinds drop out — creating a RoleBinding is a way to grant
     * yourself more than you were given, so a "restricted" mode that still allowed it
     * would not be restricted at all.
     */
    public Set<String> effectiveAllowedKinds() {
        return privilegedFeatures.isEnabled()
            ? ALLOWED_KINDS
            : ManifestValidation.withoutRbacKinds(ALLOWED_KINDS);
    }

    public List<String> allowedKinds() {
        return effectiveAllowedKinds().stream().sorted().toList();
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
            var parsed = ManifestValidation.parseAndValidate(yaml, ns, effectiveAllowedKinds());

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
