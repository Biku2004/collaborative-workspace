package com.example.collaborativeworkspace.collaboration.transport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import jakarta.websocket.Session;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

class SocketTransportLimitsTest {
    @Test void deadlinesReachNativeSessionThroughDecoratorsAndUseTomcatLongType() {
        Session nativeSession = mock(Session.class);
        Map<String, Object> properties = new HashMap<>();
        when(nativeSession.getUserProperties()).thenReturn(properties);
        var socket = mock(StandardWebSocketSession.class);
        when(socket.getNativeSession(Session.class)).thenReturn(nativeSession);
        SocketTransportLimits.configure(new WebSocketSessionDecorator(socket));
        assertEquals(2500L, properties.get("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT"));
        assertEquals(250L, properties.get("org.apache.tomcat.websocket.ABNORMAL_SESSION_CLOSE_SEND_TIMEOUT"));
        assertEquals(1000L, properties.get("org.apache.tomcat.websocket.SESSION_CLOSE_TIMEOUT"));
        assertTrue(properties.values().stream().allMatch(value -> value instanceof Long));
    }
}
