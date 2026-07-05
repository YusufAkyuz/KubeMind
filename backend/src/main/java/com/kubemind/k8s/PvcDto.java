package com.kubemind.k8s;

import java.util.List;

public record PvcDto(
    String name,
    String namespace,
    String status,
    String volumeName,
    String capacity,
    List<String> accessModes,
    String storageClass,
    String creationTimestamp
) {}
