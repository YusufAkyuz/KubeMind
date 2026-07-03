package com.kubemind.k8s;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/clusters/{clusterId}")
public class DeploymentController {

    private final KubernetesService kubernetesService;

    public DeploymentController(KubernetesService kubernetesService) {
        this.kubernetesService = kubernetesService;
    }

    @GetMapping("/namespaces/{ns}/deployments")
    public List<DeploymentDto> deployments(@PathVariable long clusterId, @PathVariable String ns) {
        return kubernetesService.listDeployments(clusterId, ns);
    }
}
