package com.example.collaborativeworkspace.collaboration.transport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@Profile("dev")
@EnableWebSocket
@org.springframework.scheduling.annotation.EnableScheduling
public class WorkspaceSocketConfiguration implements WebSocketConfigurer {
    private final WorkspaceSocketHandler handler;
    private final WorkspaceSocketHandshake handshake;
    private final String allowedOrigin;
    public WorkspaceSocketConfiguration(WorkspaceSocketHandler handler, WorkspaceSocketHandshake handshake,
            @Value("${workspace.websocket.allowed-origin:http://127.0.0.1:8080}") String allowedOrigin) {
        if (!allowedOrigin.matches("https?://[A-Za-z0-9.\\-]+(?::[0-9]{1,5})?")) throw new IllegalArgumentException("A single explicit WebSocket origin is required");
        this.handler = handler; this.handshake = handshake; this.allowedOrigin = allowedOrigin;
    }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/workspaces/{workspaceId}/socket")
                .addInterceptors(handshake).setAllowedOrigins(allowedOrigin);
    }
}
