package com.example.collaborativeworkspace.collaboration.transport;

import com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateRepository;
import com.example.collaborativeworkspace.documents.DocumentRepository;
import com.example.collaborativeworkspace.workspaces.WorkspaceRepository;
import com.example.collaborativeworkspace.workspaces.WorkspaceRole;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

class RoomAuthorizationTest {
    private WebSocketSession socket(UUID workspace, UUID user) {
        var socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(UUID.randomUUID().toString()); when(socket.isOpen()).thenReturn(true);
        when(socket.getAttributes()).thenReturn(Map.of("workspaceId", workspace.toString(), "userId", user.toString(),
                "userEmail", "test@example.com", "httpSession", mock(HttpSession.class)));
        return socket;
    }
    @Test void lateAuthorizationResultsCannotPermitFrames() {
        var documents = mock(DocumentRepository.class); var workspaces = mock(WorkspaceRepository.class); var clock = new AtomicLong();
        var hub = new DocumentRoomHub(documents, workspaces, JsonMapper.builder().build(), clock::get);
        UUID workspace = UUID.randomUUID(), user = UUID.randomUUID(), document = UUID.randomUUID();
        try {
            var peer = hub.add(socket(workspace, user));
            when(documents.canRead(workspace, document, user)).thenReturn(true);
            assertTrue(peer.allowed(document));
            when(documents.canRead(workspace, document, user)).thenAnswer(invocation -> {
                clock.addAndGet(DocumentRoomHub.MAX_AUTH_CHECK_NANOS + 1); return true;
            });
            assertFalse(peer.allowed(document));
            when(workspaces.memberRole(workspace, user)).thenAnswer(invocation -> {
                clock.addAndGet(DocumentRoomHub.MAX_AUTH_CHECK_NANOS + 1); return Optional.of(WorkspaceRole.EDITOR);
            });
            assertFalse(peer.workspaceAllowed());
            // Even a fast successful result cannot resurrect a peer invalidated during the query.
            when(documents.canRead(workspace, document, user)).thenAnswer(invocation -> { hub.invalidate(workspace, user); return true; });
            assertFalse(peer.allowed(document));
        } finally { hub.shutdown(); }
    }
    @Test void allAffectedPeersAreMarkedBeforeASlowCloseAndOtherWorkspacesSurvive() throws Exception {
        var hub = new DocumentRoomHub(mock(DocumentRepository.class), mock(WorkspaceRepository.class), JsonMapper.builder().build());
        UUID workspace = UUID.randomUUID(), user = UUID.randomUUID();
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first = socket(workspace, user); var second = socket(workspace, user); var other = socket(UUID.randomUUID(), user);
            var a = hub.add(first); var b = hub.add(second); var c = hub.add(other);
            for (var socket : java.util.List.of(first, second)) doAnswer(invocation -> {
                started.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Close test timed out"); return null;
            }).when(socket).close(CloseStatus.POLICY_VIOLATION);
            var closed = executor.submit(() -> hub.invalidate(workspace, user));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(a.sessionValid()); assertFalse(b.sessionValid()); assertTrue(c.sessionValid());
            release.countDown(); closed.get(5, TimeUnit.SECONDS);
            verify(first).close(CloseStatus.POLICY_VIOLATION); verify(second).close(CloseStatus.POLICY_VIOLATION);
            assertSame(c, hub.get(other));
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS); hub.shutdown(); }
    }
    @Test void failedOrStaleHeadReauthorizationClosesWithoutSendingContent() throws Exception {
        var updates = mock(DocumentUpdateRepository.class); var clock = new AtomicLong(); var json = JsonMapper.builder().build();
        var hub = new DocumentRoomHub(mock(DocumentRepository.class), mock(WorkspaceRepository.class), json, clock::get);
        UUID workspace = UUID.randomUUID(), user = UUID.randomUUID(), document = UUID.randomUUID();
        try {
            for (boolean failure : new boolean[] {true, false}) {
                var socket = socket(workspace, user); var peer = hub.add(socket); peer.document = document; peer.roomReady = true;
                doAnswer(invocation -> {
                    if (failure) throw new org.springframework.dao.DataAccessResourceFailureException("fixture unavailable");
                    clock.addAndGet(DocumentRoomHub.MAX_AUTH_CHECK_NANOS + 1);
                    return Optional.of(new DocumentUpdateRepository.Snapshot(0, 0, new byte[] {0, 0}, WorkspaceRole.EDITOR, false, "PLAIN_TEXT_V1"));
                }).when(updates).findAccessible(workspace, document, user);
                hub.notifyHeads(updates, json);
                verify(socket).close(CloseStatus.POLICY_VIOLATION); verify(socket, never()).sendMessage(any());
            }
        } finally { hub.shutdown(); }
    }
}
