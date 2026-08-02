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
 * Creates cluster-scoped resources from a user-authored YAML manifest —
 * currently just ClusterRole/ClusterRoleBinding (2026-07: maintainer accepted
 * the privilege-escalation risk this reopens; see ResourceCreationService's
 * class comment for the full trade-off). Separate from ResourceCreationService
 * because cluster-scoped manifests have no namespace to validate against — same
 * split as ResourceEditService's getYaml vs getYamlClusterScoped. Every
 * attempt, successful or not, is audited.
 */
@Service
public class ClusterResourceCreationService {

    public static final Set<String> CLUSTER_ALLOWED_KINDS = Set.of("ClusterRole", "ClusterRoleBinding");

    private static final int MAX_YAML_BYTES = 64 * 1024;

    private final ClusterClientFactory clientFactory;
    private final AuditService auditService;
    private final PrivilegedFeatures privilegedFeatures;

    public ClusterResourceCreationService(ClusterClientFactory clientFactory, AuditService auditService,
                                          PrivilegedFeatures privilegedFeatures) {
        this.clientFactory = clientFactory;
        this.auditService = auditService;
        this.privilegedFeatures = privilegedFeatures;
    }

    /** Everything this service can create is an RBAC object, so on a deployment
     *  running without cluster-admin the whole feature is off — nothing to list. */
    public List<String> allowedKinds() {
        return privilegedFeatures.isEnabled()
            ? CLUSTER_ALLOWED_KINDS.stream().sorted().toList()
            : List.of();
    }

    public ResourceCreationService.CreatedResourceDto create(String username, long clusterId, String yaml) {
        var identity = ManifestValidation.extractBestEffort(yaml);
        String ref = identity.kind() + "/" + identity.name();
        Map<String, Object> payload = Map.of("kind", identity.kind());

        try {
            if (!privilegedFeatures.isEnabled()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Creating cluster-scoped RBAC objects is disabled on this deployment "
                    + "(it runs without cluster-admin).");
            }
            if (yaml.getBytes().length > MAX_YAML_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Manifest is too large");
            }
            var parsed = ManifestValidation.parseAndValidateClusterScoped(yaml, CLUSTER_ALLOWED_KINDS);

            clientFactory.getClient(clusterId).resource(yaml).create();

            auditService.record(username, clusterId, "CREATE_RESOURCE", ref, payload, true, null);
            return new ResourceCreationService.CreatedResourceDto(parsed.kind(), parsed.name(), null);
        } catch (Exception e) {
            auditService.record(username, clusterId, "CREATE_RESOURCE", ref, payload, false, e.getMessage());
            throw e;
        }
    }
}
