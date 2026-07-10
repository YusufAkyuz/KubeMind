package com.kubemind.k8s;

public record HpaDto(
    String name,
    String namespace,
    String targetRef,
    int minReplicas,
    int maxReplicas,
    int currentReplicas,
    Integer currentCpuPercent,
    Integer targetCpuPercent,
    String creationTimestamp
) {}
