package com.kubemind.k8s;

public record JobDto(
    String name,
    String namespace,
    String status,
    int succeeded,
    int failed,
    int active,
    Integer completions,
    String image,
    String startTime,
    String completionTime,
    String creationTimestamp
) {}
