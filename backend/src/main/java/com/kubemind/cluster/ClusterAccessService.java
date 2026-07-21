package com.kubemind.cluster;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

/**
 * Write-access check used by @PreAuthorize on cluster-scoped write endpoints.
 *
 * ADMIN can write anywhere. A USER can write only to clusters they registered
 * themselves — those already run under that user's own kubeconfig (see
 * ClusterClientFactory), so real Kubernetes RBAC on that kubeconfig is the
 * actual ceiling, not this check. The built-in cluster (id 0) has no owner
 * row in the DB, so it never matches here — writes to it stay ADMIN-only
 * because it's a single shared identity, not a per-user credential (see
 * ClusterClientFactory.getClient).
 */
@Service
public class ClusterAccessService {

    private final ClusterRepository repository;

    public ClusterAccessService(ClusterRepository repository) {
        this.repository = repository;
    }

    public boolean canWrite(Authentication auth, long clusterId) {
        if (isAdmin(auth)) {
            return true;
        }
        return repository.findById(clusterId)
            .map(c -> c.getCreatedBy().equals(auth.getName()) && "APPROVED".equals(c.getStatus()))
            .orElse(false);
    }

    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch("ROLE_ADMIN"::equals);
    }
}
