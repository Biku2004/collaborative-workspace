package com.example.collaborativeworkspace;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.security.MessageDigest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "WORKSPACE_RUN_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class RichDocumentInitializationTest {
    @Autowired UserRepository users;
    @Autowired WorkspaceService workspaces;
    @Autowired WorkspaceMembershipService membership;
    @Autowired DocumentService documents;
    @Autowired DocumentUpdateService writer;
    @Autowired DocumentDeliveryService delivery;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @MockitoSpyBean DocumentUpdateRepository updates;

    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry) {
        String url = TestDatabaseGuard.verifiedUrl();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("WORKSPACE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("WORKSPACE_TEST_DB_PASSWORD"));
        registry.add("workspace.dev.password", () -> "test-only-password");
        registry.add("workspace.delivery.enabled", () -> "false");
    }

    @Test void internalCreationCommitsOneBootstrapAndRollsBackFailures() throws Exception {
        UUID ownerId = UUID.randomUUID(), viewerId = UUID.randomUUID(), workspace = null;
        String owner = "rich-owner-" + ownerId + "@example.com", viewer = "rich-viewer-" + viewerId + "@example.com";
        try {
            users.create(ownerId, owner, encoder.encode("test-user-password"));
            users.create(viewerId, viewer, encoder.encode("test-user-password"));
            workspace = workspaces.create(owner, "rich initialization").id();
            membership.setRole(workspace, owner, new ChangeWorkspaceMemberRequest(viewer, WorkspaceRole.VIEWER));
            final UUID workspaceId = workspace;
            Document created = documents.create(workspaceId, owner, "Notes", "rich-key", "RICH_TEXT_V1");
            assertEquals("RICH_TEXT_V1", created.collaborationFormat());
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE documents SET collaboration_format='PLAIN_TEXT_V1' WHERE id=?", created.id()));
            byte[] bootstrap = new RichTextContract(json).initialUpdate();
            assertEquals(1L, jdbc.queryForObject("SELECT head_sequence FROM document_streams WHERE document_id=?", Long.class, created.id()));
            assertArrayEquals(bootstrap, jdbc.queryForObject("SELECT checkpoint FROM document_streams WHERE document_id=?", byte[].class, created.id()));
            assertArrayEquals(bootstrap, jdbc.queryForObject("SELECT payload FROM document_updates WHERE document_id=? AND sequence=1", byte[].class, created.id()));
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bootstrap), jdbc.queryForObject("SELECT payload_sha256 FROM document_updates WHERE document_id=? AND sequence=1", byte[].class, created.id()));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_update_outbox WHERE document_id=?", Integer.class, created.id()));
            assertEquals("SCHEMA_UNSUPPORTED", assertThrows(DocumentUpdateException.class,
                    () -> writer.submit(workspaceId, created.id(), ownerId, 0, UUID.randomUUID(), bootstrap, () -> true)).code());
            assertEquals("SCHEMA_UNSUPPORTED", assertThrows(DocumentUpdateException.class,
                    () -> delivery.sync(workspaceId, created.id(), ownerId, 0, 0)).code());
            assertEquals(created, documents.create(workspaceId, owner, "Notes", "rich-key", "RICH_TEXT_V1"));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM documents WHERE workspace_id=?", Integer.class, workspaceId));
            assertThrows(IdempotencyConflictException.class, () -> documents.create(workspaceId, owner, "Notes", "rich-key", "PLAIN_TEXT_V1"));
            assertThrows(AccessDeniedException.class, () -> documents.create(workspaceId, viewer, "Viewer", null, "RICH_TEXT_V1"));
            var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> documents.create(workspaceId, owner, "Concurrent", "concurrent-rich", "RICH_TEXT_V1"));
            var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> documents.create(workspaceId, owner, "Concurrent", "concurrent-rich", "RICH_TEXT_V1"));
            Document concurrent = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(concurrent, second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Integer.class, concurrent.id()));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_update_outbox WHERE document_id=?", Integer.class, concurrent.id()));
            doThrow(new DataIntegrityViolationException("injected outbox failure")).when(updates).insertOutbox(any(), eq(0L), eq(1L));
            assertThrows(DataIntegrityViolationException.class, () -> documents.create(workspaceId, owner, "Failed", "failed-key", "RICH_TEXT_V1"));
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM documents WHERE workspace_id=?", Integer.class, workspaceId));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM creation_requests WHERE user_id=? AND request_key='failed-key'", Integer.class, ownerId));
        } finally {
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", workspace); jdbc.update("DELETE FROM workspaces WHERE id=?", workspace); }
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?)", ownerId, viewerId);
        }
    }
}
