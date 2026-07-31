package com.kubemind.cluster;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

/**
 * Who may reach which cluster.
 *
 * Self-service, like a desktop Kubernetes client: you bring your own kubeconfig
 * and you work through it. A user reaches exactly the clusters they registered
 * themselves, and what they can do there is decided by that kubeconfig's own
 * RBAC — not by an app-level role. Hand someone a read-only ServiceAccount
 * kubeconfig and KubeMind physically cannot write with it.
 *
 * This class only answers "is this door yours"; the cluster answers "what may
 * you do inside".
 *
 * The built-in cluster (id 0) is ADMIN-only. It has no owner row because it
 * isn't anyone's kubeconfig — it's the single identity this installation runs
 * as, usually bound to cluster-admin.
 */
@Service
public class ClusterAccessService {

    private final ClusterRepository repository;

    public ClusterAccessService(ClusterRepository repository) {
        this.repository = repository;
    }

    /** May this user reach the cluster at all (read included)? */
    public boolean canRead(Authentication auth, long clusterId) {
        if (isAdmin(auth)) {
            return true;
        }
        if (clusterId == ClusterClientFactory.DEFAULT_CLUSTER_ID) {
            return false; // shared installation identity, never per-user
        }
        return repository.findById(clusterId)
            .map(c -> c.getCreatedBy().equals(auth.getName()) && "APPROVED".equals(c.getStatus()))
            .orElse(false);
    }

    /**
     * May this user write here? Same door as {@link #canRead} — the difference
     * between reading and writing is enforced by the cluster's own RBAC, which
     * is the whole point of scoping people to a kubeconfig rather than to an
     * app-level role.
     */
    public boolean canWrite(Authentication auth, long clusterId) {
        return canRead(auth, clusterId);
    }

    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch("ROLE_ADMIN"::equals);
    }
}
