package com.kubemind.k8s;

public record StatefulSetDto(
    String name,
    String namespace,
    int desiredReplicas,
    int readyReplicas,
    String serviceName,
    String image,
    String creationTimestamp
) {}
