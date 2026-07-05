package com.kubemind.k8s;

public record DaemonSetDto(
    String name,
    String namespace,
    int desired,
    int ready,
    int available,
    String image,
    String creationTimestamp
) {}
