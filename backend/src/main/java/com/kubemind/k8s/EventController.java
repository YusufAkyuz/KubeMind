package com.kubemind.k8s;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/clusters/{clusterId}")
public class EventController {

    private final KubernetesService kubernetesService;

    public EventController(KubernetesService kubernetesService) {
        this.kubernetesService = kubernetesService;
    }

    @GetMapping("/namespaces/{ns}/events")
    public List<EventDto> events(@PathVariable long clusterId, @PathVariable String ns) {
        return kubernetesService.listEvents(clusterId, ns);
    }
}
