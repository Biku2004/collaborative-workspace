package com.example.collaborativeworkspace.documents.history;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Immutable materializations; this repository never modifies the update log or mutable head. */
@Repository
public class DocumentCheckpointRepository {
    private final JdbcTemplate jdbc;
    public DocumentCheckpointRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Checkpoint(UUID documentId, long epoch, long sequence, String format, byte[] bytes, byte[] digest) {
        public Checkpoint { bytes = bytes.clone(); digest = digest.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        @Override public byte[] digest() { return digest.clone(); }
    }
    public record CommittedUpdate(long sequence, byte[] bytes, byte[] digest) {
        public CommittedUpdate { bytes = bytes.clone(); digest = digest.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        @Override public byte[] digest() { return digest.clone(); }
    }

    public Optional<Checkpoint> latest(UUID document, long epoch, long through) {
        return jdbc.query("SELECT document_id, epoch, through_sequence, collaboration_format, checkpoint, checkpoint_sha256 FROM document_checkpoints WHERE document_id=? AND epoch=? AND through_sequence<=? ORDER BY through_sequence DESC LIMIT 1",
                (rs, row) -> new Checkpoint(rs.getObject("document_id", UUID.class), rs.getLong("epoch"), rs.getLong("through_sequence"),
                        rs.getString("collaboration_format"), rs.getBytes("checkpoint"), rs.getBytes("checkpoint_sha256")), document, epoch, through)
                .stream().findFirst();
    }

    public List<CommittedUpdate> tail(UUID document, long epoch, long after, long through, int limit) {
        if (limit < 1 || limit > 16) throw new IllegalArgumentException("Invalid checkpoint batch size");
        return jdbc.query("SELECT sequence, payload, payload_sha256 FROM document_updates WHERE document_id=? AND epoch=? AND sequence>? AND sequence<=? ORDER BY sequence LIMIT ?",
                (rs, row) -> new CommittedUpdate(rs.getLong("sequence"), rs.getBytes("payload"), rs.getBytes("payload_sha256")),
                document, epoch, after, through, limit);
    }

    /** A duplicate boundary is never overwritten; the service must check its existing value. */
    public boolean insert(Checkpoint checkpoint) {
        return jdbc.update("INSERT INTO document_checkpoints(document_id, epoch, through_sequence, collaboration_format, checkpoint, checkpoint_sha256) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                checkpoint.documentId(), checkpoint.epoch(), checkpoint.sequence(), checkpoint.format(), checkpoint.bytes(), checkpoint.digest()) == 1;
    }
}
