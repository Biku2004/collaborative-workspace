package com.example.collaborativeworkspace.collaboration.transport;

import jakarta.websocket.Session;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

/** Deadlines for the pinned Tomcat blocking transport; queue bounds alone cannot release a stuck writer. */
final class SocketTransportLimits {
    static final long WRITE_TIMEOUT_MS = 2500, CLOSE_SEND_TIMEOUT_MS = 250, CLOSE_HANDSHAKE_TIMEOUT_MS = 1000;
    private SocketTransportLimits() {}
    static void configure(WebSocketSession socket) {
        while (socket instanceof WebSocketSessionDecorator decorator) socket = decorator.getDelegate();
        if (socket instanceof NativeWebSocketSession nativeSocket) {
            Session session = nativeSocket.getNativeSession(Session.class);
            if (session != null) {
                var properties = session.getUserProperties();
                properties.put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", WRITE_TIMEOUT_MS);
                properties.put("org.apache.tomcat.websocket.ABNORMAL_SESSION_CLOSE_SEND_TIMEOUT", CLOSE_SEND_TIMEOUT_MS);
                properties.put("org.apache.tomcat.websocket.SESSION_CLOSE_TIMEOUT", CLOSE_HANDSHAKE_TIMEOUT_MS);
            }
        }
    }
}
