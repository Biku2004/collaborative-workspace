package com.example.collaborativeworkspace.search;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The projection is derived; every user-facing query joins current document and membership rows. */
@Repository
public class DocumentSearchRepository {
    public record Source(UUID documentId, UUID workspaceId, long epoch, long sequence, String format, byte[] state) {
        public Source { state = state.clone(); }
        @Override public byte[] state() { return state.clone(); }
    }
    public record Match(UUID documentId, String title, String format, String excerpt, long epoch, long throughSequence, long head, Instant indexedAt, boolean stale) {}
    public record Coverage(long totalDocuments, long currentDocuments, long pendingDocuments) {}
    private final JdbcTemplate jdbc;
    public DocumentSearchRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private final org.springframework.jdbc.core.RowMapper<Source> source = (rs, row) -> new Source(rs.getObject("document_id", UUID.class), rs.getObject("workspace_id", UUID.class),
            rs.getLong("epoch"), rs.getLong("head_sequence"), rs.getString("collaboration_format"), rs.getBytes("checkpoint"));
    public List<Source> pending(boolean rich, int limit) {
        if (limit < 1 || limit > 4) throw new IllegalArgumentException("Invalid index batch limit");
        return jdbc.query("SELECT s.document_id,d.workspace_id,s.epoch,s.head_sequence,d.collaboration_format,s.checkpoint FROM document_streams s JOIN documents d ON d.id=s.document_id LEFT JOIN document_search_projection p ON p.document_id=d.id " +
                "WHERE d.workspace_id IS NOT NULL AND NOT d.archived AND (d.collaboration_format='PLAIN_TEXT_V1' OR (? AND d.collaboration_format='RICH_TEXT_V1')) " +
                "AND (p.epoch IS NULL OR p.epoch<>s.epoch OR p.through_sequence<>s.head_sequence) " +
                "AND (p.document_id IS NULL OR p.next_attempt_at<=CURRENT_TIMESTAMP OR p.attempt_epoch<>s.epoch OR p.attempt_sequence<>s.head_sequence) " +
                "ORDER BY p.attempted_at NULLS FIRST,d.id LIMIT ?", source, rich, limit);
    }
    public Optional<Source> current(UUID workspace, UUID document) {
        return jdbc.query("SELECT s.document_id,d.workspace_id,s.epoch,s.head_sequence,d.collaboration_format,s.checkpoint FROM documents d JOIN document_streams s ON s.document_id=d.id WHERE d.workspace_id=? AND d.id=? AND NOT d.archived",
                source, workspace, document).stream().findFirst();
    }
    public void publish(Source source, String content, byte[] digest) {
        jdbc.update("INSERT INTO document_search_projection(document_id,epoch,through_sequence,content,source_sha256,indexed_at,attempt_epoch,attempt_sequence) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP,?,?) " +
                "ON CONFLICT(document_id) DO UPDATE SET epoch=EXCLUDED.epoch,through_sequence=EXCLUDED.through_sequence,content=EXCLUDED.content,source_sha256=EXCLUDED.source_sha256,indexed_at=CURRENT_TIMESTAMP," +
                "attempt_epoch=EXCLUDED.attempt_epoch,attempt_sequence=EXCLUDED.attempt_sequence,attempted_at=CURRENT_TIMESTAMP,next_attempt_at=CURRENT_TIMESTAMP,failure_count=0,failure_code=NULL",
                source.documentId(), source.epoch(), source.sequence(), content, digest, source.epoch(), source.sequence());
    }
    public void failed(Source source, String code) {
        if (!java.util.Set.of("ENGINE_UNAVAILABLE", "SOURCE_REJECTED", "INDEX_UNAVAILABLE").contains(code)) throw new IllegalArgumentException("Invalid failure code");
        jdbc.update("INSERT INTO document_search_projection(document_id,attempt_epoch,attempt_sequence,next_attempt_at,failure_count,failure_code) " +
                "SELECT ?,?,?,CURRENT_TIMESTAMP+INTERVAL '5 seconds',1,? WHERE EXISTS(SELECT 1 FROM document_streams WHERE document_id=?) " +
                "ON CONFLICT(document_id) DO UPDATE SET attempt_epoch=EXCLUDED.attempt_epoch,attempt_sequence=EXCLUDED.attempt_sequence,attempted_at=CURRENT_TIMESTAMP," +
                "next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '5 seconds',failure_count=LEAST(document_search_projection.failure_count+1,1000),failure_code=EXCLUDED.failure_code " +
                "WHERE (document_search_projection.attempt_epoch,document_search_projection.attempt_sequence)<=(EXCLUDED.attempt_epoch,EXCLUDED.attempt_sequence)",
                source.documentId(), source.epoch(), source.sequence(), code, source.documentId());
    }
    public List<Match> search(UUID workspace, UUID actor, String query, int limit, boolean rich) {
        if (limit < 1 || limit > 51) throw new IllegalArgumentException("Invalid search limit");
        return jdbc.query("SELECT d.id,d.title,d.collaboration_format,left(p.content,240) AS excerpt,p.epoch,p.through_sequence,s.head_sequence,p.indexed_at FROM document_search_projection p " +
                "JOIN documents d ON d.id=p.document_id JOIN document_streams s ON s.document_id=d.id JOIN workspace_members m ON m.workspace_id=d.workspace_id " +
                "CROSS JOIN websearch_to_tsquery('simple',?) AS q(term) WHERE d.workspace_id=? AND m.user_id=? AND NOT d.archived AND p.epoch=s.epoch " +
                "AND (d.collaboration_format='PLAIN_TEXT_V1' OR (? AND d.collaboration_format='RICH_TEXT_V1')) AND p.search_vector @@ q.term " +
                "ORDER BY ts_rank(p.search_vector,q.term) DESC,d.id LIMIT ?", (rs, row) -> new Match(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("collaboration_format"),
                        rs.getString("excerpt"), rs.getLong("epoch"), rs.getLong("through_sequence"), rs.getLong("head_sequence"), rs.getTimestamp("indexed_at").toInstant(),
                        rs.getLong("through_sequence") != rs.getLong("head_sequence")), query, workspace, actor, rich, limit);
    }
    public Coverage coverage(UUID workspace, UUID actor, boolean rich) {
        return jdbc.queryForObject("SELECT count(*) AS total,count(*) FILTER(WHERE p.epoch=s.epoch AND p.through_sequence=s.head_sequence) AS current FROM documents d " +
                "JOIN document_streams s ON s.document_id=d.id JOIN workspace_members m ON m.workspace_id=d.workspace_id LEFT JOIN document_search_projection p ON p.document_id=d.id " +
                "WHERE d.workspace_id=? AND m.user_id=? AND NOT d.archived AND (d.collaboration_format='PLAIN_TEXT_V1' OR (? AND d.collaboration_format='RICH_TEXT_V1'))",
                (rs, row) -> new Coverage(rs.getLong("total"), rs.getLong("current"), rs.getLong("total") - rs.getLong("current")), workspace, actor, rich);
    }
    public int clear(UUID workspace) {
        return jdbc.update("DELETE FROM document_search_projection WHERE document_id IN (SELECT id FROM documents WHERE workspace_id=?)", workspace);
    }
}
