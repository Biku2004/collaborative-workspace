package com.example.collaborativeworkspace;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentUpdateRepository {
    static final byte[] EMPTY_CHECKPOINT = { 0, 0 };
    private final JdbcTemplate jdbc;
    public DocumentUpdateRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Snapshot(long epoch, long sequence, byte[] checkpoint, WorkspaceRole role, boolean archived) {}
    public record Existing(long sequence, UUID actor, byte[] payload) {}

    public Optional<Snapshot> findAccessible(UUID workspace, UUID document, UUID user) {
        return jdbc.query("SELECT d.archived, m.role, s.epoch, s.head_sequence, s.checkpoint FROM documents d JOIN workspace_members m ON m.workspace_id=d.workspace_id LEFT JOIN document_streams s ON s.document_id=d.id WHERE d.workspace_id=? AND d.id=? AND m.user_id=?",
                this::snapshot, workspace, document, user).stream().findFirst();
    }

    private Snapshot snapshot(ResultSet rs, int row) throws SQLException {
        byte[] checkpoint = rs.getBytes("checkpoint");
        return new Snapshot(rs.getLong("epoch"), rs.getLong("head_sequence"), checkpoint == null ? EMPTY_CHECKPOINT.clone() : checkpoint,
                WorkspaceRole.valueOf(rs.getString("role")), rs.getBoolean("archived"));
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
        jdbc.update("INSERT INTO document_update_outbox(event_id, document_id, epoch, sequence) VALUES (?, ?, ?, ?)", UUID.randomUUID(), document, epoch, sequence);
    }
}
