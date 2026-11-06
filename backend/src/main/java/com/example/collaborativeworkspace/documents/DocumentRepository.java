package com.example.collaborativeworkspace.documents;

import java.util.UUID;
import java.util.List;
import java.util.Optional;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentRepository {
    private final JdbcTemplate jdbc;

    public DocumentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Document create(UUID id, UUID workspaceId, String title) {
        return create(id, workspaceId, title, "PLAIN_TEXT_V1");
    }

    public Document create(UUID id, UUID workspaceId, String title, String format) {
        return jdbc.queryForObject("INSERT INTO documents (id, workspace_id, title, collaboration_format) VALUES (?, ?, ?, ?) RETURNING id, workspace_id, title, created_at, version, archived, collaboration_format",
            this::mapDocument, id, workspaceId, title, format);
    }

    public Optional<Document> findVisible(UUID id, UUID userId) {
        return jdbc.query("SELECT d.id, d.workspace_id, d.title, d.created_at, d.version, d.archived, d.collaboration_format FROM documents d JOIN workspace_members m ON m.workspace_id = d.workspace_id WHERE d.id = ? AND m.user_id = ?",
                this::mapDocument, id, userId)
                .stream().findFirst();
    }

    public List<Document> listVisible(UUID workspaceId, UUID userId, DocumentCursor cursor, int limit) {
        String prefix = "SELECT d.id, d.workspace_id, d.title, d.created_at, d.version, d.archived, d.collaboration_format FROM workspace_members m LEFT JOIN documents d ON d.workspace_id = m.workspace_id AND NOT d.archived ";
        String filter = cursor == null ? "" : "AND (d.created_at, d.id) > (?, ?) ";
        String sql = prefix + filter + "WHERE m.workspace_id = ? AND m.user_id = ? ORDER BY d.created_at, d.id LIMIT ?";
        org.springframework.jdbc.core.RowMapper<Document> mapper = (rs, row) -> {
            UUID id = rs.getObject("id", UUID.class);
            return id == null ? null : mapDocument(rs, row);
        };
        return cursor == null ? jdbc.query(sql, mapper, workspaceId, userId, limit)
                : jdbc.query(sql, mapper, Timestamp.from(cursor.createdAt()), cursor.id(), workspaceId, userId, limit);
    }

    public boolean existsInWorkspace(UUID workspaceId, UUID documentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM documents WHERE workspace_id = ? AND id = ?)", Boolean.class, workspaceId, documentId));
    }

    public boolean canRead(UUID workspaceId, UUID documentId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM documents d JOIN workspace_members m ON m.workspace_id = d.workspace_id WHERE d.workspace_id = ? AND d.id = ? AND m.user_id = ?)", Boolean.class, workspaceId, documentId, userId));
    }

    public Optional<Document> rename(UUID workspaceId, UUID id, long version, String title) {
        return jdbc.query("UPDATE documents SET title = ?, version = version + 1 WHERE workspace_id = ? AND id = ? AND version = ? AND NOT archived RETURNING id, workspace_id, title, created_at, version, archived, collaboration_format",
                this::mapDocument, title, workspaceId, id, version).stream().findFirst();
    }

    public Optional<Document> archive(UUID workspaceId, UUID id, long version) {
        return jdbc.query("UPDATE documents SET archived = true, version = version + 1 WHERE workspace_id = ? AND id = ? AND version = ? AND NOT archived RETURNING id, workspace_id, title, created_at, version, archived, collaboration_format",
                this::mapDocument, workspaceId, id, version).stream().findFirst();
    }

    private Document mapDocument(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Document(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class), rs.getString("title"),
                rs.getTimestamp("created_at").toInstant(), rs.getLong("version"), rs.getBoolean("archived"), rs.getString("collaboration_format"));
    }
}
