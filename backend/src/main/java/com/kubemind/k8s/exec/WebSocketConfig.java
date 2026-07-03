package com.kubemind.k8s.exec;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ExecWebSocketHandler execHandler;

    public WebSocketConfig(ExecWebSocketHandler execHandler) {
        this.execHandler = execHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Same-origin only (behind the app's own ingress); params carried as query string.
        registry.addHandler(execHandler, "/ws/exec");
    }
}
