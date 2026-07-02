package com.kubemind.k8s;

import com.kubemind.audit.AuditService;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * All cluster WRITE operations live here, and every one of them — success or
 * failure — goes through the audit log. Do not add a write path anywhere else.
 */
@Service
public class KubernetesWriteService {

    static final int MAX_REPLICAS = 500;

    private final KubernetesClient client;
    private final AuditService auditService;

    public KubernetesWriteService(KubernetesClient client, AuditService auditService) {
        this.client = client;
        this.auditService = auditService;
    }

    // ── Scale ─────────────────────────────────────────────────────────────────

    public DeploymentDto scaleDeployment(String username, String ns, String name, int replicas) {
        if (replicas < 0 || replicas > MAX_REPLICAS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "replicas must be between 0 and " + MAX_REPLICAS);
        }
        String ref = "Deployment/" + ns + "/" + name;
        Map<String, Object> payload = Map.of("replicas", replicas);
        try {
            var existing = client.apps().deployments().inNamespace(ns).withName(name).get();
            if (existing == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ref + " not found");
            }
            var scaled = client.apps().deployments().inNamespace(ns).withName(name).scale(replicas);
            auditService.record(username, "SCALE_DEPLOYMENT", ref, payload, true, null);
            return toDto(scaled);
        } catch (Exception e) {
            auditService.record(username, "SCALE_DEPLOYMENT", ref, payload, false, e.getMessage());
            throw e;
        }
    }

    // ── Rollout restart ───────────────────────────────────────────────────────

    public DeploymentDto restartDeployment(String username, String ns, String name) {
        String ref = "Deployment/" + ns + "/" + name;
        try {
            var existing = client.apps().deployments().inNamespace(ns).withName(name).get();
            if (existing == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ref + " not found");
            }
            var restarted = client.apps().deployments().inNamespace(ns).withName(name)
                .rolling().restart();
            auditService.record(username, "RESTART_DEPLOYMENT", ref, null, true, null);
            return toDto(restarted);
        } catch (Exception e) {
            auditService.record(username, "RESTART_DEPLOYMENT", ref, null, false, e.getMessage());
            throw e;
        }
    }

    // ── Delete pod ────────────────────────────────────────────────────────────

    public void deletePod(String username, String ns, String name) {
        String ref = "Pod/" + ns + "/" + name;
        try {
            var existing = client.pods().inNamespace(ns).withName(name).get();
            if (existing == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ref + " not found");
            }
            client.pods().inNamespace(ns).withName(name).delete();
            auditService.record(username, "DELETE_POD", ref, null, true, null);
        } catch (Exception e) {
            auditService.record(username, "DELETE_POD", ref, null, false, e.getMessage());
            throw e;
        }
    }

    // ── YAML get / apply ──────────────────────────────────────────────────────

    public String getDeploymentYaml(String ns, String name) {
        var deployment = client.apps().deployments().inNamespace(ns).withName(name).get();
        if (deployment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Deployment/" + ns + "/" + name + " not found");
        }
        // managedFields is server bookkeeping — pure noise in an editor.
        deployment.getMetadata().setManagedFields(null);
        return Serialization.asYaml(deployment);
    }

    public DeploymentDto applyDeploymentYaml(String username, String ns, String name, String yaml) {
        String ref = "Deployment/" + ns + "/" + name;
        // Payload stores a size marker, not the full YAML — keep the audit table lean;
        // the resulting state is queryable from the cluster itself.
        Map<String, Object> payload = Map.of("yamlBytes", yaml != null ? yaml.length() : 0);
        try {
            if (yaml == null || yaml.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is empty");
            }

            Deployment parsed;
            try {
                parsed = Serialization.unmarshal(yaml, Deployment.class);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid YAML: " + rootMessage(e));
            }
            if (parsed == null || parsed.getMetadata() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "YAML does not describe a Deployment");
            }
            // The edited manifest must still be THIS deployment — renaming or moving
            // it via the editor would silently create a different resource.
            if (!name.equals(parsed.getMetadata().getName())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "metadata.name must remain '" + name + "'");
            }
            String parsedNs = parsed.getMetadata().getNamespace();
            if (parsedNs != null && !ns.equals(parsedNs)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "metadata.namespace must remain '" + ns + "'");
            }
            parsed.getMetadata().setNamespace(ns);

            var updated = client.apps().deployments().inNamespace(ns).resource(parsed).update();
            auditService.record(username, "EDIT_DEPLOYMENT_YAML", ref, payload, true, null);
            return toDto(updated);
        } catch (Exception e) {
            auditService.record(username, "EDIT_DEPLOYMENT_YAML", ref, payload, false, e.getMessage());
            throw e;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private DeploymentDto toDto(Deployment d) {
        var meta = d.getMetadata();
        var spec = d.getSpec();
        var status = d.getStatus();
        String image = spec != null && spec.getTemplate() != null
            && spec.getTemplate().getSpec() != null
            && !spec.getTemplate().getSpec().getContainers().isEmpty()
            ? spec.getTemplate().getSpec().getContainers().get(0).getImage() : null;
        return new DeploymentDto(
            meta.getName(),
            meta.getNamespace(),
            spec != null && spec.getReplicas() != null ? spec.getReplicas() : 0,
            status != null && status.getReadyReplicas() != null ? status.getReadyReplicas() : 0,
            status != null && status.getAvailableReplicas() != null ? status.getAvailableReplicas() : 0,
            spec != null && spec.getStrategy() != null && spec.getStrategy().getType() != null
                ? spec.getStrategy().getType() : "RollingUpdate",
            image,
            meta.getCreationTimestamp()
        );
    }

    private String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage();
    }
}
