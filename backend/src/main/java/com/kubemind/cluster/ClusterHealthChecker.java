package com.kubemind.cluster;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Refreshes lastCheckedAt / lastCheckOk for stored clusters every 5 minutes. */
@Component
public class ClusterHealthChecker {

    private final ClusterRepository repository;
    private final ClusterService clusterService;

    public ClusterHealthChecker(ClusterRepository repository, ClusterService clusterService) {
        this.repository = repository;
        this.clusterService = clusterService;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void checkAll() {
        repository.findAll().forEach(clusterService::refreshHealth);
    }
}
