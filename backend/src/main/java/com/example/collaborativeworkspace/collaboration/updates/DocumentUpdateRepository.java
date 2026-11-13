package com.example.collaborativeworkspace.collaboration.updates;

import com.example.collaborativeworkspace.documents.Document;
import com.example.collaborativeworkspace.workspaces.WorkspaceRole;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentUpdateRepository {
    private static final byte[] EMPTY_CHECKPOINT = { 0, 0 };
    public static byte[] emptyCheckpoint() { return EMPTY_CHECKPOINT.clone(); }
    private final JdbcTemplate jdbc;
    public DocumentUpdateRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Snapshot(long epoch, long sequence, byte[] checkpoint, WorkspaceRole role, boolean archived, String collaborationFormat) {}
    public record Existing(long sequence, UUID actor, byte[] payload) {}
    public record Update(UUID documentId, long epoch, long sequence, UUID updateId, UUID actor, byte[] payload) {}
    public record PendingEvent(UUID eventId, UUID workspaceId, Update update, UUID correlationId) {}

    public Optional<Snapshot> findAccessible(UUID workspace, UUID document, UUID user) {
        return jdbc.query("SELECT d.archived, d.collaboration_format, m.role, s.epoch, s.head_sequence, s.checkpoint FROM documents d JOIN workspace_members m ON m.workspace_id=d.workspace_id LEFT JOIN document_streams s ON s.document_id=d.id WHERE d.workspace_id=? AND d.id=? AND m.user_id=?",
                this::snapshot, workspace, document, user).stream().findFirst();
    }

    private Snapshot snapshot(ResultSet rs, int row) throws SQLException {
        byte[] checkpoint = rs.getBytes("checkpoint");
        return new Snapshot(rs.getLong("epoch"), rs.getLong("head_sequence"), checkpoint == null ? EMPTY_CHECKPOINT.clone() : checkpoint,
                WorkspaceRole.valueOf(rs.getString("role")), rs.getBoolean("archived"), rs.getString("collaboration_format"));
    }

    public boolean lockDocument(UUID workspace, UUID document) {
        return !jdbc.query("SELECT id FROM documents WHERE workspace_id=? AND id=? FOR UPDATE", (rs, row) -> rs.getObject("id", UUID.class), workspace, document).isEmpty();
    }

    public void ensureStream(UUID document) {
        jdbc.update("INSERT INTO document_streams(document_id, checkpoint) VALUES (?, ?) ON CONFLICT DO NOTHING", document, EMPTY_CHECKPOINT);
    }

    public Optional<Existing> findUpdate(UUID document, long epoch, UUID updateId) {
        return jdbc.query("SELECT sequence, actor_id, payload FROM document_updates WHERE document_id=? AND epoch=? AND update_id=?",
                (rs, row) -> new Existing(rs.getLong("sequence"), rs.getObject("actor_id", UUID.class), rs.getBytes("payload")), document, epoch, updateId).stream().findFirst();
    }

    public void saveUpdate(UUID document, long epoch, long sequence, UUID updateId, UUID actor, byte[] payload, byte[] digest) {
        jdbc.update("INSERT INTO document_updates(document_id, epoch, sequence, update_id, actor_id, payload, payload_sha256) VALUES (?, ?, ?, ?, ?, ?, ?)",
                document, epoch, sequence, updateId, actor, payload, digest);
    }

    public void saveHead(UUID document, long epoch, long sequence, byte[] checkpoint) {
        if (jdbc.update("UPDATE document_streams SET head_sequence=?, checkpoint=? WHERE document_id=? AND epoch=?", sequence, checkpoint, document, epoch) != 1)
            throw new IllegalStateException("Document stream changed unexpectedly");
    }

    public void insertOutbox(UUID document, long epoch, long sequence) {
        insertOutbox(document, epoch, sequence, UUID.randomUUID());
    }
    public void insertOutbox(UUID document, long epoch, long sequence, UUID correlationId) {
        jdbc.update("INSERT INTO document_update_outbox(event_id, document_id, epoch, sequence, correlation_id) VALUES (?, ?, ?, ?, ?)", UUID.randomUUID(), document, epoch, sequence, correlationId);
    }

    public List<Update> findAfter(UUID document, long epoch, long after, long through, int limit) {
        return jdbc.query("SELECT document_id, epoch, sequence, update_id, actor_id, payload FROM document_updates WHERE document_id=? AND epoch=? AND sequence>? AND sequence<=? ORDER BY sequence LIMIT ?",
                (rs, row) -> mapUpdate(rs), document, epoch, after, through, limit);
    }

    public List<PendingEvent> lockPendingEvents(int limit) {
        return jdbc.query("SELECT o.event_id, o.correlation_id, d.workspace_id, u.document_id, u.epoch, u.sequence, u.update_id, u.actor_id, u.payload FROM document_update_outbox o JOIN document_updates u ON u.document_id=o.document_id AND u.epoch=o.epoch AND u.sequence=o.sequence JOIN documents d ON d.id=o.document_id WHERE o.published_at IS NULL ORDER BY o.document_id, o.epoch, o.sequence LIMIT ? FOR UPDATE OF o SKIP LOCKED",
                (rs, row) -> new PendingEvent(rs.getObject("event_id", UUID.class), rs.getObject("workspace_id", UUID.class), mapUpdate(rs), rs.getObject("correlation_id", UUID.class)), limit);
    }

    /** Resolve only stored updates in the current document epoch. */
    public Optional<PendingEvent> committedEvent(UUID event) {
        return jdbc.query("SELECT o.event_id,o.correlation_id,d.workspace_id,u.document_id,u.epoch,u.sequence,u.update_id,u.actor_id,u.payload " +
                "FROM document_update_outbox o JOIN document_updates u ON u.document_id=o.document_id AND u.epoch=o.epoch AND u.sequence=o.sequence " +
                "JOIN document_streams s ON s.document_id=u.document_id AND s.epoch=u.epoch JOIN documents d ON d.id=u.document_id WHERE o.event_id=?",
                (rs, row) -> new PendingEvent(rs.getObject("event_id", UUID.class), rs.getObject("workspace_id", UUID.class), mapUpdate(rs), rs.getObject("correlation_id", UUID.class)), event).stream().findFirst();
    }

    // The column records an attempted enqueue, not confirmed client receipt.
    public void markRelayAttempted(UUID eventId) {
        if (jdbc.update("UPDATE document_update_outbox SET published_at=CURRENT_TIMESTAMP WHERE event_id=? AND published_at IS NULL", eventId) != 1)
            throw new IllegalStateException("Outbox event was not pending");
    }

    private Update mapUpdate(ResultSet rs) throws SQLException {
        return new Update(rs.getObject("document_id", UUID.class), rs.getLong("epoch"), rs.getLong("sequence"),
                rs.getObject("update_id", UUID.class), rs.getObject("actor_id", UUID.class), rs.getBytes("payload"));
    }
}
