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
        // Spring's built-in origin check compares scheme+host+port, and behind
        // nginx's `Host $host` the port never matches (see SameHostOriginInterceptor),
        // so it's switched off with "*" and replaced by a hostname-only check. That
        // check is the real guard here, not defence-in-depth: session auth can't stop
        // another page from opening a socket with this browser's session cookie.
        var sameHost = new SameHostOriginInterceptor();
        registry.addHandler(execHandler, "/ws/exec").setAllowedOriginPatterns("*").addInterceptors(sameHost);
        registry.addHandler(nodeExecHandler, "/ws/exec-node").setAllowedOriginPatterns("*").addInterceptors(sameHost);
        registry.addHandler(clusterTerminalHandler, "/ws/exec-cluster").setAllowedOriginPatterns("*").addInterceptors(sameHost);
    }
}
