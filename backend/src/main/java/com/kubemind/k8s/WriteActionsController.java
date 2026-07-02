package com.kubemind.k8s;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cluster write actions (Phase 4). Every endpoint is ADMIN-only and audited
 * inside {@link KubernetesWriteService}.
 */
@RestController
@RequestMapping("/api/k8s")
@PreAuthorize("hasRole('ADMIN')")
public class WriteActionsController {

    private final KubernetesWriteService writeService;

    public WriteActionsController(KubernetesWriteService writeService) {
        this.writeService = writeService;
    }

    public record ScaleRequest(@Min(0) @Max(KubernetesWriteService.MAX_REPLICAS) int replicas) {}

    @PostMapping("/namespaces/{ns}/deployments/{name}/scale")
    public DeploymentDto scale(@PathVariable String ns, @PathVariable String name,
                               @Valid @RequestBody ScaleRequest request, Authentication auth) {
        return writeService.scaleDeployment(auth.getName(), ns, name, request.replicas());
    }

    @PostMapping("/namespaces/{ns}/deployments/{name}/restart")
    public DeploymentDto restart(@PathVariable String ns, @PathVariable String name,
                                 Authentication auth) {
        return writeService.restartDeployment(auth.getName(), ns, name);
    }

    @DeleteMapping("/namespaces/{ns}/pods/{name}")
    public ResponseEntity<Void> deletePod(@PathVariable String ns, @PathVariable String name,
                                          Authentication auth) {
        writeService.deletePod(auth.getName(), ns, name);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/namespaces/{ns}/deployments/{name}/yaml",
                produces = "application/yaml")
    public String getYaml(@PathVariable String ns, @PathVariable String name) {
        return writeService.getDeploymentYaml(ns, name);
    }

    @PutMapping(value = "/namespaces/{ns}/deployments/{name}/yaml",
                consumes = {MediaType.TEXT_PLAIN_VALUE, "application/yaml"})
    public DeploymentDto applyYaml(@PathVariable String ns, @PathVariable String name,
                                   @RequestBody String yaml, Authentication auth) {
        return writeService.applyDeploymentYaml(auth.getName(), ns, name, yaml);
    }
}
