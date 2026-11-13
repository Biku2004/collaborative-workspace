package com.example.collaborativeworkspace.collaboration.cluster;

import com.example.collaborativeworkspace.collaboration.engine.CollaborationFormatPolicy;
import com.example.collaborativeworkspace.collaboration.transport.DocumentRoomHub;
import com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateRepository;
import com.example.collaborativeworkspace.observability.WorkspaceTelemetry;
import com.example.collaborativeworkspace.workspaces.WorkspaceAccessReduced;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

/** Redis carries authenticated hints and disposable presence, never authoritative edits or sessions. */
public final class ClusterBridge implements ClusterPublisher, MessageListener {
    static final int MAX_MESSAGE_BYTES = 4096;
    enum Kind { UPDATE, PRESENCE, LEFT, ACCESS_REDUCED }
    record Signal(int version, UUID origin, Kind kind, UUID event, UUID workspace, UUID document, UUID user,
            UUID presence, long epoch, String anchor, String head, long sentAt) {}
    record Envelope(String payload, String mac) {}
    private final UUID node = UUID.randomUUID();
    private final byte[] key, channel;
    private final String topic;
    private final RedisConnectionFactory redis;
    private final Executor ephemeral;
    private final DocumentUpdateRepository updates;
    private final DocumentRoomHub rooms;
    private final JdbcTemplate jdbc;
    private final CollaborationFormatPolicy formats;
    private final JsonMapper json;
    private final WorkspaceTelemetry telemetry;
    private final Clock clock;

    ClusterBridge(String topic, String encodedKey, RedisConnectionFactory redis, Executor ephemeral,
            DocumentUpdateRepository updates, DocumentRoomHub rooms, JdbcTemplate jdbc, CollaborationFormatPolicy formats,
            JsonMapper json, WorkspaceTelemetry telemetry, Clock clock) {
        try { key = Base64.getDecoder().decode(encodedKey); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Cluster key must be Base64-encoded random bytes"); }
        if (key.length != 32) throw new IllegalArgumentException("Cluster key must contain 32 random bytes");
        this.topic = topic; channel = topic.getBytes(StandardCharsets.UTF_8); this.redis = redis; this.ephemeral = ephemeral;
        this.updates = updates; this.rooms = rooms; this.jdbc = jdbc; this.formats = formats;
        this.json = json; this.telemetry = telemetry; this.clock = clock;
    }
    public String topic() { return topic; }

    @Override public void update(UUID event) {
        publish(new Signal(1, node, Kind.UPDATE, event, null, null, null, null, 0, null, null, clock.millis()));
    }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void reduced(WorkspaceAccessReduced event) {
        enqueue(() -> publish(new Signal(1, node, Kind.ACCESS_REDUCED, null, event.workspaceId(), null, event.userId(), null, 0, null, null, clock.millis())));
    }
    @EventListener
    public void presence(LocalPresence event) {
        enqueue(() -> {
            var frame = json.readTree(event.frame());
            boolean left = "PRESENCE_LEFT".equals(frame.path("type").asText());
            if (left) {
                publish(new Signal(1, node, Kind.LEFT, null, event.workspace(), event.document(), event.user(), event.presence(), 0, null, null, clock.millis()));
                return;
            }
            var current = updates.findAccessible(event.workspace(), event.document(), event.user());
            if (current.isEmpty() || current.get().archived()) return;
            publish(new Signal(1, node, Kind.PRESENCE, null, event.workspace(), event.document(), event.user(), event.presence(),
                    current.get().epoch(), frame.path("anchor").asText(), frame.path("head").asText(), clock.millis()));
        });
    }
    private void enqueue(Runnable task) {
        try { ephemeral.execute(() -> { try { task.run(); } catch (RuntimeException ex) { telemetry.signal(WorkspaceTelemetry.Signal.CLUSTER_UNAVAILABLE); } }); }
        catch (RejectedExecutionException ex) { telemetry.signal(WorkspaceTelemetry.Signal.CLUSTER_DROPPED); }
    }
    private byte[] signature(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(channel); mac.update((byte) 0); return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("Cluster signing unavailable"); }
    }
    byte[] encode(Signal signal) {
        String payload = json.writeValueAsString(signal);
        byte[] value = json.writeValueAsString(new Envelope(payload, HexFormat.of().formatHex(signature(payload)))).getBytes(StandardCharsets.UTF_8);
        if (value.length > MAX_MESSAGE_BYTES) throw new IllegalArgumentException("Cluster signal exceeds limit");
        return value;
    }
    private void publish(Signal signal) {
        telemetry.measure(WorkspaceTelemetry.Stage.CLUSTER_PUBLISH, () -> {
            try (var connection = redis.getConnection()) { connection.publish(channel, encode(signal)); }
            return null;
        });
    }
    @Override public void onMessage(Message message, byte[] pattern) {
        try {
            if (!java.util.Arrays.equals(channel, message.getChannel()) || message.getBody().length > MAX_MESSAGE_BYTES) throw new IllegalArgumentException();
            Envelope envelope = json.readValue(message.getBody(), Envelope.class);
            if (envelope == null || envelope.payload() == null || envelope.mac() == null || !envelope.mac().matches("[0-9a-f]{64}") ||
                    !MessageDigest.isEqual(signature(envelope.payload()), HexFormat.of().parseHex(envelope.mac()))) throw new IllegalArgumentException();
            Signal signal = json.readValue(envelope.payload(), Signal.class);
            long now = clock.millis();
            if (signal == null || signal.version() != 1 || signal.kind() == null || signal.origin() == null ||
                    signal.sentAt() < now - 10000 || signal.sentAt() > now + 5000) throw new IllegalArgumentException();
            if (node.equals(signal.origin())) return;
            telemetry.measure(WorkspaceTelemetry.Stage.CLUSTER_RECEIVE, () -> { receive(signal); return null; });
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException ex) { telemetry.signal(WorkspaceTelemetry.Signal.CLUSTER_INVALID); }
        catch (RuntimeException ex) { telemetry.signal(WorkspaceTelemetry.Signal.CLUSTER_UNAVAILABLE); }
    }
    private void receive(Signal signal) {
        if (signal.kind() == Kind.UPDATE) {
            if (signal.event() == null) throw new IllegalArgumentException();
            updates.committedEvent(signal.event()).ifPresent(event -> {
                var update = event.update();
                String frame = json.writeValueAsString(Map.of("type", "UPDATE", "version", 1, "documentId", update.documentId(),
                        "epoch", update.epoch(), "sequence", update.sequence(), "updateId", update.updateId(), "userId", update.actor(),
                        "payload", Base64.getEncoder().encodeToString(update.payload())));
                rooms.broadcastCommitted(event.workspaceId(), update.documentId(), frame, event.correlationId());
            });
            return;
        }
        if (signal.workspace() == null || signal.user() == null) throw new IllegalArgumentException();
        if (signal.kind() == Kind.ACCESS_REDUCED) { rooms.invalidate(signal.workspace(), signal.user()); return; }
        if (signal.document() == null || signal.presence() == null) throw new IllegalArgumentException();
        if (signal.kind() == Kind.LEFT) {
            rooms.remotePresence(signal.workspace(), signal.document(), json.writeValueAsString(Map.of("type", "PRESENCE_LEFT", "version", 1,
                    "documentId", signal.document(), "presenceId", signal.presence())));
            return;
        }
        if (!position(signal.anchor()) || !position(signal.head()) || signal.epoch() < 0) throw new IllegalArgumentException();
        var source = updates.findAccessible(signal.workspace(), signal.document(), signal.user());
        if (source.isEmpty() || source.get().archived() || source.get().epoch() != signal.epoch() || !formats.supports(source.get().collaborationFormat())) return;
        var emails = jdbc.query("SELECT email FROM app_users WHERE id=?", (rs, row) -> rs.getString(1), signal.user());
        if (emails.isEmpty()) return;
        rooms.remotePresence(signal.workspace(), signal.document(), json.writeValueAsString(Map.of("type", "PRESENCE", "version", 1,
                "documentId", signal.document(), "presenceId", signal.presence(), "userId", signal.user(), "email", emails.getFirst(),
                "anchor", signal.anchor(), "head", signal.head())));
    }
    private static boolean position(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > 172) return false;
        try { byte[] bytes = Base64.getDecoder().decode(encoded); return bytes.length <= 128 && Base64.getEncoder().encodeToString(bytes).equals(encoded); }
        catch (IllegalArgumentException ex) { return false; }
    }
}
