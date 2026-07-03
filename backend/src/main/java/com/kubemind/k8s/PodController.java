package com.kubemind.k8s;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/clusters/{clusterId}")
public class PodController {

    private final KubernetesService kubernetesService;

    public PodController(KubernetesService kubernetesService) {
        this.kubernetesService = kubernetesService;
    }

    @GetMapping("/namespaces/{ns}/pods")
    public List<PodDto> pods(@PathVariable long clusterId, @PathVariable String ns) {
        return kubernetesService.listPods(clusterId, ns);
    }

    @GetMapping("/namespaces/{ns}/pods/{pod}")
    public ResponseEntity<PodDto> pod(@PathVariable long clusterId,
                                      @PathVariable String ns, @PathVariable String pod) {
        var dto = kubernetesService.getPod(clusterId, ns, pod);
        return dto != null ? ResponseEntity.ok(dto) : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
}
