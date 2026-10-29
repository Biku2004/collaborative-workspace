package com.example.collaborativeworkspace;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentRepository {
    private final JdbcTemplate jdbc;

    public DocumentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Document create(UUID id, String title) {
        return jdbc.queryForObject("INSERT INTO documents (id, title) VALUES (?, ?) RETURNING id, title, created_at",
            (rs, row) -> new Document(rs.getObject("id", UUID.class), rs.getString("title"), rs.getTimestamp("created_at").toInstant()), id, title);
    }

    public Optional<Document> find(UUID id) {
        return jdbc.query("SELECT id, title, created_at FROM documents WHERE id = ?",
            (rs, row) -> new Document(rs.getObject("id", UUID.class), rs.getString("title"), rs.getTimestamp("created_at").toInstant()), id).stream().findFirst();
    }
}
