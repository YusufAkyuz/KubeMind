package com.kubemind.k8s;

public record EventDto(
    String name,
    String namespace,
    String type,
    String reason,
    String message,
    String involvedObjectKind,
    String involvedObjectName,
    int count,
    String lastTimestamp,
    String firstTimestamp
) {}
