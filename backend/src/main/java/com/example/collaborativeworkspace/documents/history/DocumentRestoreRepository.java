package com.example.collaborativeworkspace.documents.history;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentRestoreRepository {
    public record Restored(UUID id, UUID revisionId, long fromEpoch, long fromSequence, long epoch) {}
    private final JdbcTemplate jdbc;
    public DocumentRestoreRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<Restored> retry(UUID document, UUID actor, UUID request) {
        return jdbc.query("SELECT id, revision_id, from_epoch, from_sequence, to_epoch FROM document_restore_events WHERE document_id=? AND actor_id=? AND request_id=?",
                (rs, row) -> new Restored(rs.getObject("id", UUID.class), rs.getObject("revision_id", UUID.class), rs.getLong("from_epoch"), rs.getLong("from_sequence"), rs.getLong("to_epoch")),
                document, actor, request).stream().findFirst();
    }
    public Restored commit(UUID document, UUID actor, RestoreRevisionRequest request, byte[] state) {
        long nextEpoch = Math.addExact(request.expectedEpoch(), 1);
        if (jdbc.update("UPDATE document_streams SET epoch=?, head_sequence=0, checkpoint=? WHERE document_id=? AND epoch=? AND head_sequence=?",
                nextEpoch, state, document, request.expectedEpoch(), request.expectedHead()) != 1) throw new RevisionException("HEAD_CHANGED");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO document_restore_events(id, document_id, revision_id, actor_id, request_id, from_epoch, from_sequence, to_epoch) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, document, request.revisionId(), actor, request.requestId(), request.expectedEpoch(), request.expectedHead(), nextEpoch);
        return new Restored(id, request.revisionId(), request.expectedEpoch(), request.expectedHead(), nextEpoch);
    }
}
