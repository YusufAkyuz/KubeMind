package com.kubemind.k8s;

import java.util.List;

public record RoleBindingDto(
    String name,
    String namespace,
    String roleRefKind,
    String roleRefName,
    List<RbacSubjectDto> subjects,
    String creationTimestamp,
    /** Auto-created by Kubernetes/system namespaces, not by a user — see RbacFilters. */
    boolean systemManaged
) {}
