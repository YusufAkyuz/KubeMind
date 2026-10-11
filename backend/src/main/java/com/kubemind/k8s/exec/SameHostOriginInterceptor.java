package com.kubemind.k8s.exec;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/**
 * Accepts a terminal WebSocket handshake only from a page served by this same
 * host — the defence against cross-site WebSocket hijacking.
 *
 * Session auth doesn't cover that: the browser attaches KubeMind's session
 * cookie to a handshake no matter which page opened it, and WebSockets have no
 * CORS. Without this, any page that can get the browser to send that cookie —
 * at minimum anything on a sibling subdomain, which is same-site — could open
 * a cluster-admin terminal as the logged-in user.
 *
 * Hostnames only, not scheme/port: behind nginx's {@code Host $host} the port
 * the browser used never reaches us (`kubectl port-forward` to :8080 still
 * arrives as plain "localhost"), which is why Spring's built-in same-origin
 * check rejected every legitimate handshake. A handshake without an Origin
 * header isn't from a browser and so isn't a hijacking vector.
 */
public class SameHostOriginInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String origin = request.getHeaders().getOrigin();
        if (origin == null) {
            return true;
        }
        if (sameHost(origin, request.getURI().getHost())) {
            return true;
        }
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    static boolean sameHost(String origin, String requestHost) {
        if (requestHost == null) {
            return false;
        }
        try {
            // "null" (sandboxed/opaque origins) parses with no host and is refused.
            String originHost = URI.create(origin).getHost();
            return originHost != null && originHost.equalsIgnoreCase(requestHost);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
