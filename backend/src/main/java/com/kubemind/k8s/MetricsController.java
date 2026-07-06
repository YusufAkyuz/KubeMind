package com.kubemind.k8s;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Actual CPU/memory usage from metrics-server. Empty lists mean it isn't installed on the cluster. */
@RestController
@RequestMapping("/api/clusters/{clusterId}")
public class MetricsController {

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/metrics/nodes")
    public List<NodeMetricsDto> nodeMetrics(@PathVariable long clusterId) {
        return metricsService.listNodeMetrics(clusterId);
    }

    @GetMapping("/namespaces/{ns}/metrics/pods")
    public List<PodMetricsDto> podMetrics(@PathVariable long clusterId, @PathVariable String ns) {
        return metricsService.listPodMetrics(clusterId, ns);
    }
}
