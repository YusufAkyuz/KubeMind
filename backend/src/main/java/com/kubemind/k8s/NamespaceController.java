package com.kubemind.k8s;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/clusters/{clusterId}")
public class NamespaceController {

    private final KubernetesService kubernetesService;

    public NamespaceController(KubernetesService kubernetesService) {
        this.kubernetesService = kubernetesService;
    }

    @GetMapping("/namespaces")
    public List<NamespaceDto> namespaces(@PathVariable long clusterId) {
        return kubernetesService.listNamespaces(clusterId);
    }
}
