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
 * The built-in cluster (id 0) has no owner row — it isn't anyone's kubeconfig,
 * it's the single identity this installation runs as. Who may reach it depends
 * on whether impersonation is switched on:
 *
 * <ul>
 *   <li><b>off</b> — ADMIN-only. Every caller would share one usually
 *       cluster-admin-bound identity, so there is no per-user scope to grant.</li>
 *   <li><b>on</b> — open to any authenticated user. Calls now carry the
 *       caller's own identity (see ClusterClientFactory), so Kubernetes RBAC
 *       scopes each person individually and an app-level gate here would only
 *       be a second, weaker copy of that decision.</li>
 * </ul>
 */
@Service
public class ClusterAccessService {

    private final ClusterRepository repository;
    private final ImpersonationProperties impersonation;

    public ClusterAccessService(ClusterRepository repository, ImpersonationProperties impersonation) {
        this.repository = repository;
        this.impersonation = impersonation;
    }

    /** May this user reach the cluster at all (read included)? */
    public boolean canRead(Authentication auth, long clusterId) {
        if (isAdmin(auth)) {
            return true;
        }
        if (clusterId == ClusterClientFactory.DEFAULT_CLUSTER_ID) {
            // Impersonation on: the cluster itself decides what this person can
            // see, so opening the door here grants nothing on its own. Off: a
            // shared installation identity, never per-user.
            return impersonation.enabled();
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
