package com.kubemind.k8s;

public record NodeDto(
    String name,
    String status,
    String roles,
    String kubeletVersion,
    String osImage,
    String cpuCapacity,
    String memoryCapacity,
    String cpuAllocatable,
    String memoryAllocatable,
    String creationTimestamp
) {}
