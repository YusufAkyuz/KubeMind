package com.kubemind.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Install-level switch for the three surfaces that cannot exist without
 * cluster-admin-equivalent power:
 *
 * <ul>
 *   <li>the Cluster Terminal — provisions its own ServiceAccount bound to cluster-admin</li>
 *   <li>the Node Shell — schedules a privileged, host-mounted debug pod</li>
 *   <li>writing RBAC objects — whoever can create a ClusterRoleBinding can grant
 *       themselves cluster-admin, so "restricted but can write RBAC" is not a real
 *       restriction</li>
 * </ul>
 *
 * Turning this off is what lets KubeMind run against a ServiceAccount that is not
 * bound to cluster-admin (see the chart's rbac.clusterAdmin value). It is an
 * install-wide decision, deliberately not per-cluster: it describes which features
 * this deployment offers at all. What a given cluster's own credentials may
 * actually do on top of that is a separate question, answered live against that
 * cluster's RBAC.
 *
 * Defaults to enabled so existing installs keep their current behaviour.
 */
@Component
public class PrivilegedFeatures {

    private static final Logger log = LoggerFactory.getLogger(PrivilegedFeatures.class);

    private final boolean enabled;

    public PrivilegedFeatures(@Value("${kubemind.privileged-features.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @PostConstruct
    void logMode() {
        if (enabled) {
            log.info("Privileged features ENABLED (Cluster Terminal, Node Shell, RBAC writes). "
                + "This deployment needs a cluster-admin-equivalent ServiceAccount.");
        } else {
            log.info("Privileged features DISABLED — Cluster Terminal, Node Shell and RBAC "
                + "object writes are switched off. KubeMind can run without cluster-admin.");
        }
    }
}
