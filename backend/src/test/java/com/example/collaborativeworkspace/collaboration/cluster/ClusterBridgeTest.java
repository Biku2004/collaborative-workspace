package com.example.collaborativeworkspace.collaboration.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.collaborativeworkspace.collaboration.engine.CollaborationFormatPolicy;
import com.example.collaborativeworkspace.collaboration.transport.DocumentRoomHub;
import com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateRepository;
import com.example.collaborativeworkspace.observability.WorkspaceTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class ClusterBridgeTest {
    final String topic = "workspace:unit:v1", key = Base64.getEncoder().encodeToString(new byte[32]);
    final JsonMapper json = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    final DocumentUpdateRepository updates = mock(DocumentUpdateRepository.class);
    final DocumentRoomHub rooms = mock(DocumentRoomHub.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ClusterBridge bridge(String channel, String secret) {
        return new ClusterBridge(channel, secret, mock(RedisConnectionFactory.class), Runnable::run, updates, rooms, jdbc,
                mock(CollaborationFormatPolicy.class), json, new WorkspaceTelemetry(meters), clock);
    }
    ClusterBridge.Signal hint(UUID event, long time) {
        return new ClusterBridge.Signal(1, UUID.randomUUID(), ClusterBridge.Kind.UPDATE, event, null, null, null, null, 0, null, null, time);
    }
    @Test void hintsReloadCommittedBytesAndDuplicateNotificationsNeverWrite() {
        var bridge = bridge(topic, key); UUID event = UUID.randomUUID(), workspace = UUID.randomUUID(), document = UUID.randomUUID(), actor = UUID.randomUUID(), correlation = UUID.randomUUID();
        var stored = new DocumentUpdateRepository.Update(document, 0, 1, UUID.randomUUID(), actor, new byte[] {1, 2});
        when(updates.committedEvent(event)).thenReturn(Optional.of(new DocumentUpdateRepository.PendingEvent(event, workspace, stored, correlation)));
        byte[] encoded = bridge.encode(hint(event, clock.millis()));
        for (int i = 0; i < 2; i++) bridge.onMessage(new DefaultMessage(topic.getBytes(StandardCharsets.UTF_8), encoded), null);
        var frame = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(rooms, times(2)).broadcastCommitted(eq(workspace), eq(document), frame.capture(), eq(correlation));
        assertEquals("AQI=", json.readTree(frame.getValue()).path("payload").asText());
        assertFalse(new String(encoded, StandardCharsets.UTF_8).contains("AQI="));
        verify(updates, times(2)).committedEvent(event); verifyNoMoreInteractions(updates);
    }
    @Test void wrongKeyChannelTamperingStalenessAndOversizeAreRejectedBeforeDatabaseAccess() {
        var bridge = bridge(topic, key); UUID event = UUID.randomUUID();
        byte[] otherKey = new byte[32]; otherKey[0] = 1;
        var wrong = bridge(topic, Base64.getEncoder().encodeToString(otherKey));
        byte[] tampered = bridge.encode(hint(event, clock.millis())); tampered[tampered.length - 5] ^= 1;
        for (byte[] body : new byte[][] {wrong.encode(hint(event, clock.millis())), tampered, bridge.encode(hint(event, clock.millis() - 10001)),
                bridge.encode(hint(event, Long.MIN_VALUE)), new byte[4097], "{}".getBytes(StandardCharsets.UTF_8)})
            assertDoesNotThrow(() -> bridge.onMessage(new DefaultMessage(topic.getBytes(StandardCharsets.UTF_8), body), null));
        bridge.onMessage(new DefaultMessage("other-topic".getBytes(StandardCharsets.UTF_8), bridge.encode(hint(event, clock.millis()))), null);
        verifyNoInteractions(updates, rooms);
        assertEquals(7, meters.get("workspace.transport").tag("signal", "cluster_invalid").counter().count());
    }
    @Test void unknownAndOldEpochPointersCannotInjectDocumentUpdates() {
        var bridge = bridge(topic, key); UUID event = UUID.randomUUID();
        when(updates.committedEvent(event)).thenReturn(Optional.empty());
        bridge.onMessage(new DefaultMessage(topic.getBytes(StandardCharsets.UTF_8), bridge.encode(hint(event, clock.millis()))), null);
        verifyNoInteractions(rooms);
    }
    @Test void signedPresenceStillRequiresCurrentSourceAccessAndEpoch() {
        var bridge = bridge(topic, key);
        UUID workspace = UUID.randomUUID(), document = UUID.randomUUID(), user = UUID.randomUUID();
        when(updates.findAccessible(workspace, document, user)).thenReturn(Optional.empty(),
                Optional.of(new DocumentUpdateRepository.Snapshot(2, 1, new byte[] {0, 0}, com.example.collaborativeworkspace.workspaces.WorkspaceRole.EDITOR, false, "PLAIN_TEXT_V1")));
        var presence = new ClusterBridge.Signal(1, UUID.randomUUID(), ClusterBridge.Kind.PRESENCE, null, workspace, document, user,
                UUID.randomUUID(), 1, "AA==", "AA==", clock.millis());
        for (int i = 0; i < 2; i++) bridge.onMessage(new DefaultMessage(topic.getBytes(StandardCharsets.UTF_8), bridge.encode(presence)), null);
        verifyNoInteractions(jdbc, rooms);
    }
    @Test void accessReductionHintInvalidatesOnlyItsWorkspaceAndUser() {
        var bridge = bridge(topic, key); UUID workspace = UUID.randomUUID(), user = UUID.randomUUID();
        var signal = new ClusterBridge.Signal(1, UUID.randomUUID(), ClusterBridge.Kind.ACCESS_REDUCED, null, workspace, null, user,
                null, 0, null, null, clock.millis());
        bridge.onMessage(new DefaultMessage(topic.getBytes(StandardCharsets.UTF_8), bridge.encode(signal)), null);
        verify(rooms).invalidate(workspace, user); verifyNoMoreInteractions(rooms); verifyNoInteractions(updates, jdbc);
    }
}
