package com.kubemind.k8s;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/k8s")
public class DeploymentController {

    private final KubernetesService kubernetesService;

    public DeploymentController(KubernetesService kubernetesService) {
        this.kubernetesService = kubernetesService;
    }

    @GetMapping("/namespaces/{ns}/deployments")
    public List<DeploymentDto> deployments(@PathVariable String ns) {
        return kubernetesService.listDeployments(ns);
    }
}
