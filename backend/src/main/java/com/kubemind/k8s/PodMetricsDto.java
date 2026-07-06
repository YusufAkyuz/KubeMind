package com.kubemind.k8s;

/** CPU/memory usage for a Pod (summed across containers), sourced from metrics-server. */
public record PodMetricsDto(String name, String namespace, String cpuUsage, String memoryUsage) {}
