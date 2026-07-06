package com.kubemind.k8s;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cluster write actions (Phase 4). Every endpoint is ADMIN-only and audited
 * inside {@link KubernetesWriteService}. Generic YAML create/edit lives in
 * {@link ResourceCreationController} / {@link ResourceEditController}.
 */
@RestController
@RequestMapping("/api/clusters/{clusterId}")
@PreAuthorize("hasRole('ADMIN')")
public class WriteActionsController {

    private final KubernetesWriteService writeService;

    public WriteActionsController(KubernetesWriteService writeService) {
        this.writeService = writeService;
    }

    public record ScaleRequest(@Min(0) @Max(KubernetesWriteService.MAX_REPLICAS) int replicas) {}

    @PostMapping("/namespaces/{ns}/deployments/{name}/scale")
    public DeploymentDto scaleDeployment(@PathVariable long clusterId,
                                         @PathVariable String ns, @PathVariable String name,
                                         @Valid @RequestBody ScaleRequest request, Authentication auth) {
        return writeService.scaleDeployment(auth.getName(), clusterId, ns, name, request.replicas());
    }

    @PostMapping("/namespaces/{ns}/deployments/{name}/restart")
    public DeploymentDto restartDeployment(@PathVariable long clusterId,
                                           @PathVariable String ns, @PathVariable String name,
                                           Authentication auth) {
        return writeService.restartDeployment(auth.getName(), clusterId, ns, name);
    }

    @PostMapping("/namespaces/{ns}/statefulsets/{name}/scale")
    public StatefulSetDto scaleStatefulSet(@PathVariable long clusterId,
                                           @PathVariable String ns, @PathVariable String name,
                                           @Valid @RequestBody ScaleRequest request, Authentication auth) {
        return writeService.scaleStatefulSet(auth.getName(), clusterId, ns, name, request.replicas());
    }

    @PostMapping("/namespaces/{ns}/statefulsets/{name}/restart")
    public StatefulSetDto restartStatefulSet(@PathVariable long clusterId,
                                             @PathVariable String ns, @PathVariable String name,
                                             Authentication auth) {
        return writeService.restartStatefulSet(auth.getName(), clusterId, ns, name);
    }

    @PostMapping("/namespaces/{ns}/daemonsets/{name}/restart")
    public DaemonSetDto restartDaemonSet(@PathVariable long clusterId,
                                         @PathVariable String ns, @PathVariable String name,
                                         Authentication auth) {
        return writeService.restartDaemonSet(auth.getName(), clusterId, ns, name);
    }

    @DeleteMapping("/namespaces/{ns}/pods/{name}")
    public ResponseEntity<Void> deletePod(@PathVariable long clusterId,
                                          @PathVariable String ns, @PathVariable String name,
                                          Authentication auth) {
        writeService.deletePod(auth.getName(), clusterId, ns, name);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/namespaces/{name}")
    public ResponseEntity<Void> deleteNamespace(@PathVariable long clusterId,
                                                @PathVariable String name,
                                                Authentication auth) {
        writeService.deleteNamespace(auth.getName(), clusterId, name);
        return ResponseEntity.noContent().build();
    }
}
