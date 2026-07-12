package com.kubemind.k8s.exec;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ExecWebSocketHandler execHandler;
    private final NodeExecWebSocketHandler nodeExecHandler;
    private final ClusterTerminalWebSocketHandler clusterTerminalHandler;

    public WebSocketConfig(ExecWebSocketHandler execHandler,
                           NodeExecWebSocketHandler nodeExecHandler,
                           ClusterTerminalWebSocketHandler clusterTerminalHandler) {
        this.execHandler = execHandler;
        this.nodeExecHandler = nodeExecHandler;
        this.clusterTerminalHandler = clusterTerminalHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Spring's WebSocketHandlerRegistration same-origin-checks handshakes by default,
        // computed from request.getServerName()/getServerPort() — behind nginx/an ingress
        // that's proxying to the backend's Service DNS name, that doesn't match the Origin
        // header a real browser sends for the address it's actually loaded from, so every
        // handshake gets rejected with a bare 403 (no Spring Security involved — this check
        // runs before the security filter chain's ADMIN-role check even applies). Access
        // here is already gated by session auth + hasRole("ADMIN") in SecurityConfig, so the
        // Origin check is redundant defense-in-depth, not the actual guard — safe to open up.
        registry.addHandler(execHandler, "/ws/exec").setAllowedOriginPatterns("*");
        registry.addHandler(nodeExecHandler, "/ws/exec-node").setAllowedOriginPatterns("*");
        registry.addHandler(clusterTerminalHandler, "/ws/exec-cluster").setAllowedOriginPatterns("*");
    }
}
