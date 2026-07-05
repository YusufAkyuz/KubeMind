package com.kubemind.k8s;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/clusters/{clusterId}")
public class StorageController {

    private final KubernetesService kubernetesService;

    public StorageController(KubernetesService kubernetesService) {
        this.kubernetesService = kubernetesService;
    }

    @GetMapping("/namespaces/{ns}/persistentvolumeclaims")
    public List<PvcDto> persistentVolumeClaims(@PathVariable long clusterId, @PathVariable String ns) {
        return kubernetesService.listPersistentVolumeClaims(clusterId, ns);
    }

    /** PersistentVolumes are cluster-scoped. */
    @GetMapping("/persistentvolumes")
    public List<PvDto> persistentVolumes(@PathVariable long clusterId) {
        return kubernetesService.listPersistentVolumes(clusterId);
    }
}
