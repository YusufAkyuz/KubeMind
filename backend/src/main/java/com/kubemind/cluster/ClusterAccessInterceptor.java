package com.kubemind.cluster;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces "may this user reach this cluster at all" for every
 * {@code /api/clusters/{id}/...} route in one place.
 *
 * Deliberately an interceptor rather than a @PreAuthorize on each controller:
 * there are ~29 cluster-scoped controllers and forgetting one would be a silent
 * hole, whereas a route that should be exempt has to be named explicitly here.
 * Write authorization stays where it is — this only decides whether the door
 * opens; what the caller may do once inside is the cluster's own RBAC.
 */
@Component
public class ClusterAccessInterceptor implements HandlerInterceptor {

    private final ClusterAccessService clusterAccessService;

    public ClusterAccessInterceptor(ClusterAccessService clusterAccessService) {
        this.clusterAccessService = clusterAccessService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long clusterId = extractClusterId(request.getRequestURI());
        if (clusterId == null) {
            return true; // not a cluster-scoped route (e.g. /api/clusters, /api/clusters/pending)
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            // The security filter chain should have rejected this already; if the
            // context is somehow missing, fail closed rather than assume a system call.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!clusterAccessService.canRead(auth, clusterId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "You don't have access to this cluster.");
        }
        return true;
    }

    /** /api/clusters/{id}/... -> id, or null when the segment isn't a cluster id. */
    private Long extractClusterId(String uri) {
        String[] parts = uri.split("/");
        // ["", "api", "clusters", "<id>", ...] — anything shorter has no cluster segment.
        if (parts.length < 5 || !"api".equals(parts[1]) || !"clusters".equals(parts[2])) {
            return null;
        }
        try {
            return Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            return null; // e.g. /api/clusters/pending
        }
    }
}
