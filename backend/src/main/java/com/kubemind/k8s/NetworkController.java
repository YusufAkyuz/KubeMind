package com.kubemind.k8s;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/clusters/{clusterId}")
public class NetworkController {

    private final KubernetesService kubernetesService;

    public NetworkController(KubernetesService kubernetesService) {
        this.kubernetesService = kubernetesService;
    }

    @GetMapping("/namespaces/{ns}/services")
    public List<ServiceDto> services(@PathVariable long clusterId, @PathVariable String ns) {
        return kubernetesService.listServices(clusterId, ns);
    }

    @GetMapping("/namespaces/{ns}/ingresses")
    public List<IngressDto> ingresses(@PathVariable long clusterId, @PathVariable String ns) {
        return kubernetesService.listIngresses(clusterId, ns);
    }
}
