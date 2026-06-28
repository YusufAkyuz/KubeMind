package com.kubemind.k8s;

public record DeploymentDto(
    String name,
    String namespace,
    int desiredReplicas,
    int readyReplicas,
    int availableReplicas,
    String strategy,
    String image,
    String creationTimestamp
) {}
