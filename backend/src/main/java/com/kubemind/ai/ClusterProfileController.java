package com.kubemind.ai;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of the background "Cluster Profile" job's output — see ClusterProfileService. */
@RestController
@RequestMapping("/api/clusters/{clusterId}")
public class ClusterProfileController {

    private final ClusterProfileService clusterProfileService;

    public ClusterProfileController(ClusterProfileService clusterProfileService) {
        this.clusterProfileService = clusterProfileService;
    }

    @GetMapping("/cluster-insights")
    public ClusterProfileService.ClusterInsightsDto insights(@PathVariable long clusterId) {
        return clusterProfileService.getInsights(clusterId);
    }
}
