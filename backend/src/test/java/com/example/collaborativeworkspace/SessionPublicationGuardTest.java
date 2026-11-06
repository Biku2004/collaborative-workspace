package com.example.collaborativeworkspace;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class SessionPublicationGuardTest {
    @Test void httpAndSocketPublishersShareOneSessionGuard() throws Exception {
        var registry = new SocketSessionRegistry(); var session = new MockHttpSession();
        Object guard = registry.publicationGuard(session);
        var socket = mock(WebSocketSession.class); var attributes = new HashMap<String, Object>();
        when(socket.getAttributes()).thenReturn(attributes);
        assertTrue(registry.register(session, socket));
        assertSame(guard, attributes.get("sessionGuard")); assertSame(guard, registry.publicationGuard(session));
        session.invalidate(); registry.sessionDestroyed(new HttpSessionEvent(session));
        verify(socket).close(CloseStatus.POLICY_VIOLATION); assertEquals(0, registry.count());
        assertThrows(AccessDeniedException.class, () -> registry.publicationGuard(session));
        assertFalse(registry.register(session, socket));
    }
    @Test void invalidationDuringGuardAcquisitionFailsClosed() {
        var registry = new SocketSessionRegistry(); var session = mock(HttpSession.class);
        when(session.getId()).thenReturn("invalidating-session");
        when(session.getCreationTime()).thenReturn(0L).thenThrow(new IllegalStateException("Expired"));
        assertThrows(AccessDeniedException.class, () -> registry.publicationGuard(session));
        assertThrows(AccessDeniedException.class, () -> registry.publicationGuard(null));
    }
}
