package com.kubemind.k8s;

import java.util.List;

/** Cluster-scoped ClusterRoleBinding. */
public record ClusterRoleBindingDto(
    String name,
    String roleRefKind,
    String roleRefName,
    List<RbacSubjectDto> subjects,
    String creationTimestamp,
    /** Bootstrapped by Kubernetes/an addon, not by a user — see RbacFilters. */
    boolean systemManaged
) {}
