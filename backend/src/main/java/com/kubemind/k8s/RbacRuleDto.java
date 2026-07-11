package com.kubemind.k8s;

import java.util.List;

/** One PolicyRule inside a Role/ClusterRole. */
public record RbacRuleDto(
    List<String> apiGroups,
    List<String> resources,
    List<String> resourceNames,
    List<String> verbs
) {}
