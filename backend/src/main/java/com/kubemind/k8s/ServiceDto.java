package com.kubemind.k8s;

import java.util.List;
import java.util.Map;

public record ServiceDto(
    String name,
    String namespace,
    String type,
    String clusterIP,
    List<String> ports,
    Map<String, String> selector,
    String creationTimestamp
) {}
