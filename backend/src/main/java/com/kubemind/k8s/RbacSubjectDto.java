package com.kubemind.k8s;

/** One Subject inside a RoleBinding/ClusterRoleBinding. */
public record RbacSubjectDto(
    String kind,
    String name,
    String namespace
) {}
