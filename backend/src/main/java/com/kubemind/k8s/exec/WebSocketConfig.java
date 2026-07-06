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
        // Same-origin only (behind the app's own ingress); params carried as query string.
        registry.addHandler(execHandler, "/ws/exec");
        registry.addHandler(nodeExecHandler, "/ws/exec-node");
        registry.addHandler(clusterTerminalHandler, "/ws/exec-cluster");
    }
}
