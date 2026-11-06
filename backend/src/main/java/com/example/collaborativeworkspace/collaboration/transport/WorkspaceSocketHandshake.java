package com.example.collaborativeworkspace.collaboration.transport;

import com.example.collaborativeworkspace.auth.UserRepository;
import com.example.collaborativeworkspace.workspaces.WorkspaceRepository;

import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
@Profile("dev")
public class WorkspaceSocketHandshake implements HandshakeInterceptor {
    private final UserRepository users;
    private final WorkspaceRepository workspaces;
    private final String allowedOrigin;
    public WorkspaceSocketHandshake(UserRepository users, WorkspaceRepository workspaces,
            @Value("${workspace.websocket.allowed-origin:http://127.0.0.1:8080}") String allowedOrigin) {
        this.users = users; this.workspaces = workspaces; this.allowedOrigin = allowedOrigin;
    }

    @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servlet) || !allowedOrigin.equals(request.getHeaders().getOrigin()) ||
                !(servlet.getServletRequest().getUserPrincipal() instanceof Authentication authentication) || !authentication.isAuthenticated()) {
            response.setStatusCode(HttpStatus.FORBIDDEN); return false;
        }
        HttpSession session = servlet.getServletRequest().getSession(false);
        if (session == null) { response.setStatusCode(HttpStatus.UNAUTHORIZED); return false; }
        String[] parts = request.getURI().getPath().split("/");
        UUID workspace;
        try { workspace = UUID.fromString(parts[3]); }
        catch (IllegalArgumentException | IndexOutOfBoundsException ex) { response.setStatusCode(HttpStatus.NOT_FOUND); return false; }
        var user = users.findIdByEmail(authentication.getName());
        if (user.isEmpty() || workspaces.memberRole(workspace, user.get()).isEmpty()) {
            response.setStatusCode(HttpStatus.NOT_FOUND); return false;
        }
        attributes.put("httpSession", session);
        attributes.put("httpSessionId", session.getId());
        attributes.put("workspaceId", workspace.toString());
        attributes.put("userId", user.get().toString());
        attributes.put("userEmail", authentication.getName());
        return true;
    }
    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {}
}
