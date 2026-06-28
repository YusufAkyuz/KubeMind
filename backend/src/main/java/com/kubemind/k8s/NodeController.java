package com.kubemind.k8s;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/k8s")
public class NodeController {

    private final KubernetesService kubernetesService;

    public NodeController(KubernetesService kubernetesService) {
        this.kubernetesService = kubernetesService;
    }

    @GetMapping("/nodes")
    public List<NodeDto> nodes() {
        return kubernetesService.listNodes();
    }
}
