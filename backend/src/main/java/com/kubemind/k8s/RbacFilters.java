package com.kubemind.k8s;

import io.fabric8.kubernetes.api.model.ObjectMeta;

import java.util.Map;
import java.util.Set;

/**
 * Heuristics for "did a human create this, or did Kubernetes/an addon
 * bootstrap it" — used to default the Access Control pages to hiding clutter
 * (~75 ClusterRoles on a stock kind cluster, almost none of it user-relevant).
 * Not a security boundary, just a UI filter: getting this wrong hides or shows
 * an extra row, nothing more. The frontend always lets the user toggle it off.
 */
final class RbacFilters {

    private static final Set<String> SYSTEM_NAMESPACES =
        Set.of("kube-system", "kube-public", "kube-node-lease", "local-path-storage");

    /** The four built-in aggregated ClusterRoles every cluster ships with. */
    private static final Set<String> DEFAULT_AGGREGATE_ROLES = Set.of("cluster-admin", "admin", "edit", "view");

    private RbacFilters() {}

    static boolean isSystemName(String name) {
        return name != null && (name.startsWith("system:") || DEFAULT_AGGREGATE_ROLES.contains(name));
    }

    static boolean hasBootstrapLabel(ObjectMeta meta) {
        Map<String, String> labels = meta != null ? meta.getLabels() : null;
        return labels != null && "rbac-defaults".equals(labels.get("kubernetes.io/bootstrapping"));
    }

    static boolean isSystemNamespace(String namespace) {
        return namespace != null && SYSTEM_NAMESPACES.contains(namespace);
    }

    static boolean isDefaultServiceAccount(String name) {
        return "default".equals(name);
    }
}
