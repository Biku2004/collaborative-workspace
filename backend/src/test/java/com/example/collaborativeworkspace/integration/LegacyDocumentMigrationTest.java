package com.example.collaborativeworkspace.integration;

import com.example.collaborativeworkspace.documents.Document;
import com.example.collaborativeworkspace.documents.DocumentRepository;
import com.example.collaborativeworkspace.support.TestDatabaseGuard;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

@EnabledIfEnvironmentVariable(named = "WORKSPACE_RUN_DB_TESTS", matches = "true")
class LegacyDocumentMigrationTest {
    @Test
    void v4PreservesPreexistingDocumentsAndRejectsNewUnscopedRows() throws Exception {
        String url = TestDatabaseGuard.verifiedUrl(), user = required("WORKSPACE_TEST_DB_USER"), password = required("WORKSPACE_TEST_DB_PASSWORD");
        String schema = "lesson14_" + UUID.randomUUID().toString().replace("-", "");
        UUID legacyId = UUID.randomUUID();
        try {
            Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema).target("3").load().migrate();
            try (var connection = DriverManager.getConnection(url, user, password);
                 var insert = connection.prepareStatement("INSERT INTO " + schema + ".documents (id, title) VALUES (?, ?)")) {
                insert.setObject(1, legacyId); insert.setString(2, "legacy before migration"); insert.executeUpdate();
            }
            Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema).load().migrate();
            try (var connection = DriverManager.getConnection(url, user, password);
                 var query = connection.prepareStatement("SELECT title, workspace_id FROM " + schema + ".documents WHERE id = ?")) {
                query.setObject(1, legacyId);
                try (var rs = query.executeQuery()) {
                    assertTrue(rs.next()); assertEquals("legacy before migration", rs.getString("title")); assertNull(rs.getObject("workspace_id"));
                }
                try (var insert = connection.prepareStatement("INSERT INTO " + schema + ".documents (id, title) VALUES (?, ?)")) {
                    insert.setObject(1, UUID.randomUUID()); insert.setString(2, "unscoped");
                    assertThrows(java.sql.SQLException.class, insert::executeUpdate);
                }
                connection.setSchema(schema);
                var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                UUID actor = UUID.randomUUID(), workspace = UUID.randomUUID();
                jdbc.update("INSERT INTO app_users (id, email, password_hash) VALUES (?, ?, ?)", actor, "legacy-test@example.com", "test-hash");
                jdbc.update("INSERT INTO workspaces (id, name, created_by) VALUES (?, ?, ?)", workspace, "test", actor);
                jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, 'OWNER')", workspace, actor);
                assertTrue(new DocumentRepository(jdbc).findVisible(legacyId, actor).isEmpty());
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void v6PreservesExistingScopedDocumentMetadata() throws Exception {
        String url = TestDatabaseGuard.verifiedUrl(), user = required("WORKSPACE_TEST_DB_USER"), password = required("WORKSPACE_TEST_DB_PASSWORD");
        String schema = "lesson16_" + UUID.randomUUID().toString().replace("-", "");
        UUID actor = UUID.randomUUID(), workspace = UUID.randomUUID(), document = UUID.randomUUID();
        try {
            Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema).target("5").load().migrate();
            try (var connection = DriverManager.getConnection(url, user, password)) {
                connection.setSchema(schema);
                var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                jdbc.update("INSERT INTO app_users (id, email, password_hash) VALUES (?, ?, ?)", actor, "v6-test@example.com", "test-hash");
                jdbc.update("INSERT INTO workspaces (id, name, created_by) VALUES (?, ?, ?)", workspace, "test", actor);
                jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, 'OWNER')", workspace, actor);
                jdbc.update("INSERT INTO documents (id, workspace_id, title) VALUES (?, ?, ?)", document, workspace, "Existing title");
            }
            Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema).load().migrate();
            try (var connection = DriverManager.getConnection(url, user, password)) {
                connection.setSchema(schema);
                var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                Document existing = new DocumentRepository(jdbc).findVisible(document, actor).orElseThrow();
                assertEquals("Existing title", existing.title());
                assertEquals(workspace, existing.workspaceId());
                assertEquals(0, existing.version());
                assertFalse(existing.archived());
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void v10LabelsExistingDocumentsWithoutRewritingTheirStream() throws Exception {
        String url = TestDatabaseGuard.verifiedUrl(), user = required("WORKSPACE_TEST_DB_USER"), password = required("WORKSPACE_TEST_DB_PASSWORD");
        String schema = "lesson27_" + UUID.randomUUID().toString().replace("-", "");
        UUID actor = UUID.randomUUID(), workspace = UUID.randomUUID(), document = UUID.randomUUID();
        byte[] checkpoint = {0, 0}, update = {1, 2, 3};
        try {
            Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema).target("9").load().migrate();
            try (var connection = DriverManager.getConnection(url, user, password)) {
                connection.setSchema(schema);
                var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                jdbc.update("INSERT INTO app_users (id, email, password_hash) VALUES (?, ?, ?)", actor, "v10-test@example.com", "test-hash");
                jdbc.update("INSERT INTO workspaces (id, name, created_by) VALUES (?, ?, ?)", workspace, "test", actor);
                jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, 'OWNER')", workspace, actor);
                jdbc.update("INSERT INTO documents (id, workspace_id, title) VALUES (?, ?, ?)", document, workspace, "Existing content");
                jdbc.update("INSERT INTO document_streams(document_id, epoch, head_sequence, checkpoint) VALUES (?, ?, ?, ?)", document, 0, 1, checkpoint);
                jdbc.update("INSERT INTO document_updates(document_id, epoch, sequence, update_id, actor_id, payload, payload_sha256) VALUES (?, 0, 1, ?, ?, ?, ?)",
                        document, UUID.randomUUID(), actor, update, new byte[32]);
                jdbc.update("INSERT INTO document_update_outbox(event_id, document_id, epoch, sequence) VALUES (?, ?, 0, 1)", UUID.randomUUID(), document);
            }
            Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema).target("10").load().migrate();
            try (var connection = DriverManager.getConnection(url, user, password)) {
                connection.setSchema(schema);
                var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                Document existing = new DocumentRepository(jdbc).findVisible(document, actor).orElseThrow();
                assertEquals("PLAIN_TEXT_V1", existing.collaborationFormat());
                assertEquals("Existing content", existing.title());
                assertArrayEquals(checkpoint, jdbc.queryForObject("SELECT checkpoint FROM document_streams WHERE document_id=?", byte[].class, document));
                assertArrayEquals(update, jdbc.queryForObject("SELECT payload FROM document_updates WHERE document_id=? AND sequence=1", byte[].class, document));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_update_outbox WHERE document_id=?", Integer.class, document));
                assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                        () -> jdbc.update("UPDATE documents SET collaboration_format='RICH_TEXT_V1' WHERE id=?", document));
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
    @Test
    void v14AndV15PreserveStoredDataAndKeepUpdateBounds() throws Exception {
        String url = TestDatabaseGuard.verifiedUrl(), user = required("WORKSPACE_TEST_DB_USER"), password = required("WORKSPACE_TEST_DB_PASSWORD");
        String schema = "capacity_" + UUID.randomUUID().toString().replace("-", "");
        var migrations = Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema);
        UUID actor = UUID.randomUUID(), workspace = UUID.randomUUID(), document = UUID.randomUUID();
        byte[] baseline = new byte[32768];
        new java.util.Random(27).nextBytes(baseline);
        try {
            migrations.target("13").load().migrate();
            try (var connection = DriverManager.getConnection(url, user, password)) {
                connection.setSchema(schema);
                var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                jdbc.update("INSERT INTO app_users (id,email,password_hash) VALUES (?,?,?)", actor, "migration@example.com", "fixture");
                jdbc.update("INSERT INTO workspaces (id,name,created_by) VALUES (?,?,?)", workspace, "fixture", actor);
                jdbc.update("INSERT INTO documents (id,workspace_id,title,collaboration_format) VALUES (?,?,?,'RICH_TEXT_V1')", document, workspace, "fixture");
                jdbc.update("INSERT INTO document_streams (document_id,checkpoint) VALUES (?,?)", document, baseline);
                String setCheckpoint = "UPDATE document_streams SET checkpoint=? WHERE document_id=?";
                assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                        () -> jdbc.update(setCheckpoint, new byte[32769], document));
                migrations.target("14").load().migrate();
                assertArrayEquals(baseline, jdbc.queryForObject("SELECT checkpoint FROM document_streams WHERE document_id=?", byte[].class, document));
                assertEquals(1, jdbc.update(setCheckpoint, new byte[65536], document));
                assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                        () -> jdbc.update(setCheckpoint, new byte[65537], document));
                String insertUpdate = "INSERT INTO document_updates(document_id,epoch,sequence,update_id,actor_id,payload,payload_sha256) VALUES (?,0,1,?,?,?,?)";
                assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                        () -> jdbc.update(insertUpdate, document, UUID.randomUUID(), actor, new byte[8193], new byte[32]));
                assertEquals(1, jdbc.update(insertUpdate, document, UUID.randomUUID(), actor, new byte[8192], new byte[32]));
                UUID event = UUID.randomUUID();
                jdbc.update("INSERT INTO document_update_outbox(event_id,document_id,epoch,sequence) VALUES (?,?,0,1)", event, document);
                migrations.target("15").load().migrate();
                assertEquals(65536, jdbc.queryForObject("SELECT octet_length(checkpoint) FROM document_streams WHERE document_id=?", Integer.class, document));
                assertEquals(8192, jdbc.queryForObject("SELECT octet_length(payload) FROM document_updates WHERE document_id=? AND sequence=1", Integer.class, document));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_update_outbox WHERE event_id=?", Integer.class, event));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document_checkpoints", Integer.class));
                jdbc.update("INSERT INTO document_checkpoints(document_id,epoch,through_sequence,collaboration_format,checkpoint,checkpoint_sha256) VALUES (?,0,1,'RICH_TEXT_V1',?,?)", document, new byte[] {0, 0}, new byte[32]);
                migrations.target("16").load().migrate();
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_checkpoints WHERE document_id=?", Integer.class, document));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document_revisions", Integer.class));
                migrations.target("17").load().migrate();
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_checkpoints WHERE document_id=?", Integer.class, document));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_update_outbox WHERE event_id=?", Integer.class, event));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document_restore_events", Integer.class));
                migrations.target("18").load().migrate();
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document_search_projection", Integer.class));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_checkpoints WHERE document_id=?", Integer.class, document));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_update_outbox WHERE event_id=?", Integer.class, event));
                migrations.target("19").load().migrate();
                assertEquals(event, jdbc.queryForObject("SELECT correlation_id FROM document_update_outbox WHERE event_id=?", UUID.class, event));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_checkpoints WHERE document_id=?", Integer.class, document));
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
