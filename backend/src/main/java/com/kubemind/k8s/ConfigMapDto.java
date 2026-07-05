package com.kubemind.k8s;

import java.util.Map;

public record ConfigMapDto(
    String name,
    String namespace,
    Map<String, String> data,
    int binaryDataCount,
    String creationTimestamp
) {}
