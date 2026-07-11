package com.kubemind.k8s;

import java.util.List;

public record RoleDto(
    String name,
    String namespace,
    List<RbacRuleDto> rules,
    String creationTimestamp,
    /** Auto-created by Kubernetes/system namespaces, not by a user — see RbacFilters. */
    boolean systemManaged
) {}
