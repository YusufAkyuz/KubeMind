package com.kubemind.k8s;

/** CPU/memory usage for a Node, sourced from metrics-server. Null fields mean unavailable. */
public record NodeMetricsDto(String name, String cpuUsage, String memoryUsage) {}
