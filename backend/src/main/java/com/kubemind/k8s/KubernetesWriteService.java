package com.kubemind.k8s;

import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Cluster WRITE operations that are kind-specific enough to need their own
 * typed logic (replica scaling, rollout restart, pod delete) rather than the
 * generic YAML create/edit paths. Every operation — success or failure —
 * goes through the audit log. Do not add a write path anywhere else.
 */
@Service
public class KubernetesWriteService {

    static final int MAX_REPLICAS = 500;

    private final ClusterClientFactory clientFactory;
    private final AuditService auditService;

    public KubernetesWriteService(ClusterClientFactory clientFactory, AuditService auditService) {
        this.clientFactory = clientFactory;
        this.auditService = auditService;
    }

    // ── Scale ─────────────────────────────────────────────────────────────────

    public DeploymentDto scaleDeployment(String username, long clusterId,
                                         String ns, String name, int replicas) {
        validateReplicas(replicas);
        String ref = "Deployment/" + ns + "/" + name;
        Map<String, Object> payload = Map.of("replicas", replicas);
        try {
            var client = clientFactory.getClient(clusterId);
            var existing = client.apps().deployments().inNamespace(ns).withName(name).get();
            if (existing == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ref + " not found");
            }
            var scaled = client.apps().deployments().inNamespace(ns).withName(name).scale(replicas);
            auditService.record(username, clusterId, "SCALE_RESOURCE", ref, payload, true, null);
            return toDeploymentDto(scaled);
        } catch (Exception e) {
            auditService.record(username, clusterId, "SCALE_RESOURCE", ref, payload, false, e.getMessage());
            throw e;
        }
    }

    public StatefulSetDto scaleStatefulSet(String username, long clusterId,
                                           String ns, String name, int replicas) {
        validateReplicas(replicas);
        String ref = "StatefulSet/" + ns + "/" + name;
        Map<String, Object> payload = Map.of("replicas", replicas);
        try {
            var client = clientFactory.getClient(clusterId);
            var existing = client.apps().statefulSets().inNamespace(ns).withName(name).get();
            if (existing == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ref + " not found");
            }
            var scaled = client.apps().statefulSets().inNamespace(ns).withName(name).scale(replicas);
            auditService.record(username, clusterId, "SCALE_RESOURCE", ref, payload, true, null);
            return toStatefulSetDto(scaled);
        } catch (Exception e) {
            auditService.record(username, clusterId, "SCALE_RESOURCE", ref, payload, false, e.getMessage());
            throw e;
        }
    }

    // ── Rollout restart ───────────────────────────────────────────────────────

    public DeploymentDto restartDeployment(String username, long clusterId, String ns, String name) {
        String ref = "Deployment/" + ns + "/" + name;
        try {
            var client = clientFactory.getClient(clusterId);
            var existing = client.apps().deployments().inNamespace(ns).withName(name).get();
            if (existing == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ref + " not found");
            }
            // Fabric8's .rolling().restart() sends a patch the API server rejects
            // with 422 on this cluster/version combo — replicate what
            // `kubectl rollout restart` actually does instead: stamp the pod
            // template with a restart annotation via a plain edit+update.
            var restarted = client.apps().deployments().inNamespace(ns).withName(name)
                .edit(d -> new DeploymentBuilder(d)
                    .editSpec().editTemplate().editMetadata()
                        .addToAnnotations("kubectl.kubernetes.io/restartedAt", java.time.Instant.now().toString())
                    .endMetadata().endTemplate().endSpec()
                    .build());
            auditService.record(username, clusterId, "RESTART_RESOURCE", ref, null, true, null);
            return toDeploymentDto(restarted);
        } catch (Exception e) {
            auditService.record(username, clusterId, "RESTART_RESOURCE", ref, null, false, e.getMessage());
            throw e;
        }
    }

    public StatefulSetDto restartStatefulSet(String username, long clusterId, String ns, String name) {
        String ref = "StatefulSet/" + ns + "/" + name;
        try {
            var client = clientFactory.getClient(clusterId);
            var existing = client.apps().statefulSets().inNamespace(ns).withName(name).get();
            if (existing == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ref + " not found");
            }
            var restarted = client.apps().statefulSets().inNamespace(ns).withName(name)
                .edit(s -> new StatefulSetBuilder(s)
                    .editSpec().editTemplate().editMetadata()
                        .addToAnnotations("kubectl.kubernetes.io/restartedAt", java.time.Instant.now().toString())
                    .endMetadata().endTemplate().endSpec()
                    .build());
            auditService.record(username, clusterId, "RESTART_RESOURCE", ref, null, true, null);
            return toStatefulSetDto(restarted);
        } catch (Exception e) {
            auditService.record(username, clusterId, "RESTART_RESOURCE", ref, null, false, e.getMessage());
            throw e;
        }
    }

    public DaemonSetDto restartDaemonSet(String username, long clusterId, String ns, String name) {
        String ref = "DaemonSet/" + ns + "/" + name;
        try {
            var client = clientFactory.getClient(clusterId);
            var existing = client.apps().daemonSets().inNamespace(ns).withName(name).get();
            if (existing == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ref + " not found");
            }
            var restarted = client.apps().daemonSets().inNamespace(ns).withName(name)
                .edit(d -> new DaemonSetBuilder(d)
                    .editSpec().editTemplate().editMetadata()
                        .addToAnnotations("kubectl.kubernetes.io/restartedAt", java.time.Instant.now().toString())
                    .endMetadata().endTemplate().endSpec()
                    .build());
            auditService.record(username, clusterId, "RESTART_RESOURCE", ref, null, true, null);
            return toDaemonSetDto(restarted);
        } catch (Exception e) {
            auditService.record(username, clusterId, "RESTART_RESOURCE", ref, null, false, e.getMessage());
            throw e;
        }
    }

    // ── Delete pod ────────────────────────────────────────────────────────────

    public void deletePod(String username, long clusterId, String ns, String name) {
        String ref = "Pod/" + ns + "/" + name;
        try {
            var client = clientFactory.getClient(clusterId);
            var existing = client.pods().inNamespace(ns).withName(name).get();
            if (existing == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ref + " not found");
            }
            client.pods().inNamespace(ns).withName(name).delete();
            auditService.record(username, clusterId, "DELETE_POD", ref, null, true, null);
        } catch (Exception e) {
            auditService.record(username, clusterId, "DELETE_POD", ref, null, false, e.getMessage());
            throw e;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void validateReplicas(int replicas) {
        if (replicas < 0 || replicas > MAX_REPLICAS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "replicas must be between 0 and " + MAX_REPLICAS);
        }
    }

    private DeploymentDto toDeploymentDto(Deployment d) {
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

    private StatefulSetDto toStatefulSetDto(StatefulSet s) {
        var meta = s.getMetadata();
        var spec = s.getSpec();
        var status = s.getStatus();
        String image = spec != null && spec.getTemplate() != null
            && spec.getTemplate().getSpec() != null
            && !spec.getTemplate().getSpec().getContainers().isEmpty()
            ? spec.getTemplate().getSpec().getContainers().get(0).getImage() : null;
        return new StatefulSetDto(
            meta.getName(),
            meta.getNamespace(),
            spec != null && spec.getReplicas() != null ? spec.getReplicas() : 0,
            status != null && status.getReadyReplicas() != null ? status.getReadyReplicas() : 0,
            spec != null ? spec.getServiceName() : null,
            image,
            meta.getCreationTimestamp()
        );
    }

    private DaemonSetDto toDaemonSetDto(DaemonSet d) {
        var meta = d.getMetadata();
        var spec = d.getSpec();
        var status = d.getStatus();
        String image = spec != null && spec.getTemplate() != null
            && spec.getTemplate().getSpec() != null
            && !spec.getTemplate().getSpec().getContainers().isEmpty()
            ? spec.getTemplate().getSpec().getContainers().get(0).getImage() : null;
        return new DaemonSetDto(
            meta.getName(),
            meta.getNamespace(),
            status != null && status.getDesiredNumberScheduled() != null ? status.getDesiredNumberScheduled() : 0,
            status != null && status.getNumberReady() != null ? status.getNumberReady() : 0,
            status != null && status.getNumberAvailable() != null ? status.getNumberAvailable() : 0,
            image,
            meta.getCreationTimestamp()
        );
    }
}
