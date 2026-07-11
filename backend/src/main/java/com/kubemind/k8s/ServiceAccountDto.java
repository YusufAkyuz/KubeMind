package com.kubemind.k8s;

public record ServiceAccountDto(
    String name,
    String namespace,
    int secretCount,
    int imagePullSecretCount,
    Boolean automountToken,
    String creationTimestamp,
    /** Auto-created by Kubernetes/system namespaces, not by a user — see RbacFilters. */
    boolean systemManaged
) {}
