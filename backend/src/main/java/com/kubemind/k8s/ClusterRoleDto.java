package com.kubemind.k8s;

import java.util.List;

/** Cluster-scoped ClusterRole. */
public record ClusterRoleDto(
    String name,
    List<RbacRuleDto> rules,
    String creationTimestamp,
    /** Bootstrapped by Kubernetes itself, not by a user — see RbacFilters. */
    boolean systemManaged
) {}
