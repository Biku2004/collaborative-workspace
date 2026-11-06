package com.example.collaborativeworkspace.collaboration.transport;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Component
@Profile("dev")
public class SocketSessionRegistry implements HttpSessionListener {
    private static final class SessionGuard {
        final Set<WebSocketSession> sockets = ConcurrentHashMap.newKeySet();
        boolean destroyed;
    }
    private final ConcurrentHashMap<String, SessionGuard> sessions = new ConcurrentHashMap<>();

    /** HTTP publishers and socket writers must share the guard observed by session destruction. */
    public Object publicationGuard(HttpSession http) {
        if (http == null) throw new org.springframework.security.access.AccessDeniedException("Session unavailable");
        synchronized (http) {
            try {
                http.getCreationTime();
                String id = http.getId();
                SessionGuard guard = sessions.computeIfAbsent(id, ignored -> new SessionGuard());
                synchronized (guard) {
                    try {
                        http.getCreationTime();
                        if (guard.destroyed) throw new org.springframework.security.access.AccessDeniedException("Session unavailable");
                        return guard;
                    } catch (IllegalStateException ex) {
                        sessions.remove(id, guard);
                        throw new org.springframework.security.access.AccessDeniedException("Session unavailable");
                    }
                }
            } catch (IllegalStateException ex) { throw new org.springframework.security.access.AccessDeniedException("Session unavailable"); }
        }
    }

    public boolean register(HttpSession http, WebSocketSession socket) {
        synchronized (http) {
            try {
                http.getCreationTime(); // Throws when the session was invalidated during the handshake.
                SessionGuard guard = sessions.computeIfAbsent(http.getId(), ignored -> new SessionGuard());
                synchronized (guard) {
                    try {
                        http.getCreationTime();
                        if (guard.destroyed) return false;
                        guard.sockets.add(socket);
                        socket.getAttributes().put("sessionGuard", guard);
                        return true;
                    } catch (IllegalStateException ex) {
                        sessions.remove(http.getId(), guard);
                        return false;
                    }
                }
            } catch (IllegalStateException ex) { return false; }
        }
    }

    public void remove(String id, WebSocketSession socket) {
        SessionGuard guard = sessions.get(id);
        if (guard != null) synchronized (guard) { guard.sockets.remove(socket); }
    }

    @Override public void sessionDestroyed(HttpSessionEvent event) {
        HttpSession http = event.getSession();
        SessionGuard guard = sessions.get(http.getId());
        if (guard == null) return;
        Set<WebSocketSession> closed;
        synchronized (guard) {
            guard.destroyed = true;
            sessions.remove(http.getId(), guard);
            closed = Set.copyOf(guard.sockets);
            guard.sockets.clear();
        }
        for (WebSocketSession socket : closed) {
            try { socket.close(CloseStatus.POLICY_VIOLATION); }
            catch (IOException ignored) { /* The socket may already be gone. */ }
        }
    }

    int count() { return sessions.values().stream().mapToInt(guard -> guard.sockets.size()).sum(); }
}
