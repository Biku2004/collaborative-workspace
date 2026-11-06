package com.example.collaborativeworkspace.collaboration.transport;

import com.example.collaborativeworkspace.documents.DocumentRepository;
import com.example.collaborativeworkspace.workspaces.WorkspaceRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

class PresenceRateTest {
    @Test void rateLimitResetsAfterOneSecond() {
        var hub = new DocumentRoomHub(mock(DocumentRepository.class), mock(WorkspaceRepository.class), JsonMapper.builder().build());
        try {
            var socket = mock(WebSocketSession.class);
            when(socket.getAttributes()).thenReturn(Map.of("workspaceId", UUID.randomUUID().toString(),
                    "userId", UUID.randomUUID().toString(), "userEmail", "test@example.com", "httpSession", mock(HttpSession.class)));
            var peer = hub.new Peer(socket);
            for (int i = 0; i < 10; i++) assertTrue(peer.allowPresence(1));
            assertFalse(peer.allowPresence(1));
            assertTrue(peer.allowPresence(1 + TimeUnit.SECONDS.toNanos(1)));
        } finally { hub.shutdown(); }
    }
}
