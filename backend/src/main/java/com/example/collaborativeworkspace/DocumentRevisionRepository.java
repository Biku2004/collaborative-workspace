package com.example.collaborativeworkspace;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentRevisionRepository {
    public record Summary(UUID id, String name, UUID createdBy, long epoch, long throughSequence, String format, Instant createdAt) {}
    public record Revision(UUID documentId, UUID requestId, Summary summary, byte[] bytes, byte[] digest) {
        public Revision { bytes = bytes.clone(); digest = digest.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        @Override public byte[] digest() { return digest.clone(); }
    }
    private final JdbcTemplate jdbc;
    public DocumentRevisionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private Summary summary(ResultSet rs, int row) throws SQLException {
        return new Summary(rs.getObject("id", UUID.class), rs.getString("name"), rs.getObject("created_by", UUID.class), rs.getLong("epoch"),
                rs.getLong("through_sequence"), rs.getString("collaboration_format"), rs.getTimestamp("created_at").toInstant());
    }
    private Revision revision(ResultSet rs, int row) throws SQLException {
        return new Revision(rs.getObject("document_id", UUID.class), rs.getObject("request_id", UUID.class), summary(rs, row), rs.getBytes("checkpoint"), rs.getBytes("checkpoint_sha256"));
    }
    public Optional<Revision> find(UUID document, UUID id) {
        return jdbc.query("SELECT * FROM document_revisions WHERE document_id=? AND id=?", this::revision, document, id).stream().findFirst();
    }
    public Optional<Revision> retry(UUID document, UUID actor, UUID request) {
        return jdbc.query("SELECT * FROM document_revisions WHERE document_id=? AND created_by=? AND request_id=?", this::revision, document, actor, request).stream().findFirst();
    }
    public List<Summary> list(UUID document, DocumentCursor cursor, int limit) {
        if (cursor == null) return jdbc.query("SELECT id,name,created_by,epoch,through_sequence,collaboration_format,created_at FROM document_revisions WHERE document_id=? ORDER BY created_at DESC,id DESC LIMIT ?", this::summary, document, limit);
        return jdbc.query("SELECT id,name,created_by,epoch,through_sequence,collaboration_format,created_at FROM document_revisions WHERE document_id=? AND (created_at,id)<(?,?) ORDER BY created_at DESC,id DESC LIMIT ?",
                this::summary, document, java.sql.Timestamp.from(cursor.createdAt()), cursor.id(), limit);
    }
    public Summary insert(UUID document, UUID actor, UUID request, String name, long epoch, long sequence, String format, byte[] state, byte[] digest) {
        return jdbc.queryForObject("INSERT INTO document_revisions(id,document_id,created_by,request_id,name,epoch,through_sequence,collaboration_format,checkpoint,checkpoint_sha256) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING *",
                this::summary, UUID.randomUUID(), document, actor, request, name, epoch, sequence, format, state, digest);
    }
}
