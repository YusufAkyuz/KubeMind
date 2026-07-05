package com.kubemind.k8s;

import java.util.List;

/** Cluster-scoped PersistentVolume. */
public record PvDto(
    String name,
    String status,
    String capacity,
    List<String> accessModes,
    String reclaimPolicy,
    String storageClass,
    String claimRef,
    String creationTimestamp
) {}
