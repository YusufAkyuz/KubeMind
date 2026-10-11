package com.kubemind.k8s;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Opens Service port-forward sessions (audited) and serves the ones reached
 * under a path prefix on KubeMind's own origin. See PortForwardProxy for the
 * two ways a session is reached, and PortForwardService's class comment for
 * the trust model this sits in — same tier as the Cluster Terminal.
 */
@RestController
public class PortForwardController {

    private final PortForwardService portForwardService;
    private final PortForwardProxy proxy;

    public PortForwardController(PortForwardService portForwardService, PortForwardProxy proxy) {
        this.portForwardService = portForwardService;
        this.proxy = proxy;
    }

    /** @param url where to open the session: its own origin's handoff link, or the path prefix. */
    public record OpenedSessionDto(String sessionId, String url) {}

    @PostMapping("/api/clusters/{clusterId}/namespaces/{ns}/services/{name}/forward")
    @PreAuthorize("@clusterAccessService.canWrite(authentication, #clusterId)")
    public OpenedSessionDto open(@PathVariable long clusterId, @PathVariable String ns, @PathVariable String name,
                                 @RequestParam(required = false) Integer port, Authentication auth) {
        var opened = portForwardService.open(auth.getName(), clusterId, ns, name, port);
        return new OpenedSessionDto(opened.sessionId(), opened.url());
    }

    // No cluster id in this path, so authorization is by session ownership:
    // PortForwardService ignores a session that isn't the caller's.
    @DeleteMapping("/api/port-forward/{sessionId}")
    public void close(@PathVariable String sessionId, Authentication auth) {
        portForwardService.close(auth.getName(), sessionId);
    }

    /**
     * Everything under /api/port-forward/{sessionId}/** is forwarded to the
     * session's tunnel, with the app mounted under that prefix.
     */
    @RequestMapping(value = "/api/port-forward/{sessionId}/**", method = {
        RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE,
        RequestMethod.PATCH, RequestMethod.HEAD, RequestMethod.OPTIONS
    })
    public void proxy(@PathVariable String sessionId, HttpServletRequest request,
                      HttpServletResponse response, Authentication auth) throws IOException {
        // Authorized by ownership, not by role: the session id is the only thing
        // this path carries, so someone else's tunnel must look exactly like one
        // that expired.
        PortForwardService.Target target = portForwardService.touch(sessionId, auth.getName());
        if (target == null) {
            PortForwardProxy.writeError(response, HttpServletResponse.SC_GONE,
                "This port-forward session has expired or doesn't exist. Close this tab and use Forward again.");
            return;
        }

        String prefix = "/api/port-forward/" + sessionId;
        String remainder = request.getRequestURI().substring(prefix.length());
        if (remainder.isEmpty()) {
            // Without the trailing slash, the app's relative links would resolve
            // one level above the session prefix.
            String query = request.getQueryString();
            response.sendRedirect(prefix + "/" + (query != null ? "?" + query : ""));
            return;
        }
        proxy.forward(target, sessionId, remainder, request, response, PortForwardProxy.Mount.underPrefix(prefix));
    }
}
