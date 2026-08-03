package com.kubemind.k8s.exec;

import com.kubemind.cluster.ClusterAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.security.Principal;

/**
 * "May this session reach that cluster?" for the terminal WebSockets.
 *
 * These routes live under /ws/, so ClusterAccessInterceptor — which guards
 * /api/clusters/{id}/** — never sees them, and the cluster id arrives as a
 * query parameter the caller chooses. Without this check, an authenticated
 * USER could name a cluster belonging to someone else and get a shell inside
 * it: ClusterClientFactory only verifies that a cluster is APPROVED, not who
 * it belongs to.
 *
 * A shell is a write capability regardless of what the user types into it, so
 * this asks canWrite.
 */
@Component
class ExecClusterAccessGuard {

    private final ClusterAccessService clusterAccessService;

    ExecClusterAccessGuard(ClusterAccessService clusterAccessService) {
        this.clusterAccessService = clusterAccessService;
    }

    boolean permits(WebSocketSession session, long clusterId) {
        Principal principal = session.getPrincipal();
        // Fail closed: no recognisable authentication means no cluster.
        if (!(principal instanceof Authentication auth) || !auth.isAuthenticated()) {
            return false;
        }
        return clusterAccessService.canWrite(auth, clusterId);
    }
}
