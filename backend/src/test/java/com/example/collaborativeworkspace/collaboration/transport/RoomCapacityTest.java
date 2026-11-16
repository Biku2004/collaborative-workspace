package com.example.collaborativeworkspace.collaboration.transport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.collaborativeworkspace.collaboration.engine.CollaborationFormatPolicy;
import com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateService;
import com.example.collaborativeworkspace.documents.Document;
import com.example.collaborativeworkspace.documents.DocumentRepository;
import com.example.collaborativeworkspace.observability.WorkspaceTelemetry;
import com.example.collaborativeworkspace.workspaces.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;
import tools.jackson.databind.json.JsonMapper;

class RoomCapacityTest {
    private WebSocketSession socket(UUID workspace) {
        var socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(UUID.randomUUID().toString()); when(socket.isOpen()).thenReturn(true);
        when(socket.getAttributes()).thenReturn(new HashMap<>(Map.of("workspaceId", workspace.toString(), "userId", UUID.randomUUID().toString(),
                "userEmail", "capacity@example.com", "httpSession", mock(HttpSession.class))));
        return socket;
    }
    private DocumentRoomHub hub() { return new DocumentRoomHub(mock(DocumentRepository.class), mock(WorkspaceRepository.class), JsonMapper.builder().build()); }

    @Test void concurrentJoinsIncludeReservationsAndStayWithinRoomCap() throws Exception {
        var hub = hub(); var metrics = new SimpleMeterRegistry(); hub.observe(new WorkspaceTelemetry(metrics));
        var workers = Executors.newFixedThreadPool(12); UUID workspace = UUID.randomUUID(), document = UUID.randomUUID();
        try {
            var peers = new ArrayList<DocumentRoomHub.Peer>();
            for (int i = 0; i < 48; i++) peers.add(hub.add(socket(workspace)));
            var attempts = new ArrayList<Future<Boolean>>();
            for (var peer : peers) attempts.add(workers.submit(() -> hub.join(peer, document, false)));
            int admitted = 0;
            for (var attempt : attempts) if (attempt.get(5, TimeUnit.SECONDS)) admitted++;
            assertEquals(32, admitted);
            // JOINED has not been sent yet: pending reservations must still use capacity.
            assertTrue(peers.stream().noneMatch(peer -> peer.roomReady));
            assertEquals(16, metrics.get("workspace.transport").tag("signal", "room_rejected").counter().count());
            assertNotNull(hub.add(socket(workspace))); // Global socket capacity remains independent.
        } finally { workers.shutdownNow(); workers.awaitTermination(5, TimeUnit.SECONDS); hub.shutdown(); }
    }
    @Test void leaveAndDisconnectReleaseCapacityWithoutRestrictingOtherRooms() {
        var hub = hub(); UUID workspace = UUID.randomUUID(), document = UUID.randomUUID();
        try {
            var peers = new ArrayList<DocumentRoomHub.Peer>();
            for (int i = 0; i < 32; i++) {
                var peer = hub.add(socket(workspace)); assertTrue(hub.join(peer, document, true)); peers.add(peer);
            }
            var waiting = hub.add(socket(workspace)); assertFalse(hub.join(waiting, document, false));
            var otherDocument = hub.add(socket(workspace)); assertTrue(hub.join(otherDocument, UUID.randomUUID(), false));
            var otherWorkspace = hub.add(socket(UUID.randomUUID())); assertTrue(hub.join(otherWorkspace, document, false));
            hub.leave(peers.getFirst()); assertTrue(hub.join(waiting, document, false));
            hub.remove(waiting.socket); hub.remove(waiting.socket);
            assertFalse(hub.join(waiting, document, false), "Removed peer cannot reserve again");
            assertTrue(hub.join(peers.getFirst(), document, false), "The disconnected reservation was released");
        } finally { hub.shutdown(); }
    }
    @Test void disconnectRacingJoinCannotLeakRoomReservations() throws Exception {
        var hub = hub(); var workers = Executors.newFixedThreadPool(2); UUID workspace = UUID.randomUUID(), document = UUID.randomUUID();
        try {
            for (int i = 0; i < 40; i++) {
                var peer = hub.add(socket(workspace)); var start = new CountDownLatch(1);
                var joined = workers.submit(() -> { start.await(); return hub.join(peer, document, true); });
                var removed = workers.submit(() -> { start.await(); hub.remove(peer.socket); return true; });
                start.countDown(); joined.get(5, TimeUnit.SECONDS); removed.get(5, TimeUnit.SECONDS);
                assertEquals(0, hub.count()); assertNull(peer.document);
                assertFalse(hub.join(peer, document, false));
            }
            for (int i = 0; i < 32; i++) assertTrue(hub.join(hub.add(socket(workspace)), document, false));
        } finally { workers.shutdownNow(); workers.awaitTermination(5, TimeUnit.SECONDS); hub.shutdown(); }
    }
    @Test void rejectedJoinClosesWithRetryableOverloadStatus() throws Exception {
        UUID workspace = UUID.randomUUID(), document = UUID.randomUUID();
        var documents = mock(DocumentRepository.class); var formats = mock(CollaborationFormatPolicy.class); var json = JsonMapper.builder().build();
        var hub = new DocumentRoomHub(documents, mock(WorkspaceRepository.class), json);
        try {
            for (int i = 0; i < 32; i++) assertTrue(hub.join(hub.add(socket(workspace)), document, false));
            var socket = socket(workspace); var peer = hub.add(socket); peer.welcomed = true;
            when(documents.findVisible(document, peer.user)).thenReturn(Optional.of(new Document(document, workspace, "capacity", Instant.now(), 0, false, "PLAIN_TEXT_V1")));
            when(formats.supports("PLAIN_TEXT_V1")).thenReturn(true);
            var handler = new WorkspaceSocketHandler(mock(SocketSessionRegistry.class), json, hub, mock(DocumentUpdateService.class), mock(DocumentDeliveryService.class), formats);
            handler.handleMessage(socket, new TextMessage("{\"type\":\"JOIN\",\"version\":1,\"documentId\":\"" + document + "\"}"));
            verify(socket).close(CloseStatus.SERVICE_OVERLOAD); assertNull(hub.get(socket));
        } finally { hub.shutdown(); }
    }
}
