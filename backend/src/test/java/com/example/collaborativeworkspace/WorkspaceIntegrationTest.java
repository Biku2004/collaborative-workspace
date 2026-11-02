package com.example.collaborativeworkspace;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@EnabledIfEnvironmentVariable(named = "WORKSPACE_RUN_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class WorkspaceIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired WorkspaceService service;
    @MockitoSpyBean WorkspaceRepository workspaces;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        String url = TestDatabaseGuard.verifiedUrl();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> required("WORKSPACE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> required("WORKSPACE_TEST_DB_PASSWORD"));
        registry.add("workspace.dev.password", () -> "test-only-password");
        registry.add("workspace.delivery.enabled", () -> "false");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    @Test
    void createAndListAreRestrictedToAuthenticatedMembers() throws Exception {
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        String alice = "alice-" + aliceId + "@example.com";
        String bob = "bob-" + bobId + "@example.com";
        String password = "test-user-password";
        String base = "http://127.0.0.1:" + port;
        try {
            users.create(aliceId, alice, encoder.encode(password));
            users.create(bobId, bob, encoder.encode(password));
            HttpClient anonymous = client();
            assertEquals(401, get(anonymous, base + "/api/workspaces").statusCode());
            HttpClient bootstrap = client();
            login(bootstrap, base, "learner", "test-only-password");
            assertEquals(403, get(bootstrap, base + "/api/workspaces").statusCode());
            HttpClient aliceClient = client();
            HttpClient bobClient = client();
            login(aliceClient, base, alice, password);
            login(bobClient, base, bob, password);
            String aliceToken = token(get(aliceClient, base + "/api/csrf").body());
            String bobToken = token(get(bobClient, base + "/api/csrf").body());
            assertEquals(403, post(aliceClient, base + "/api/workspaces", "{\"name\":\"Test\"}", null).statusCode());
            assertEquals(400, post(aliceClient, base + "/api/workspaces", "{\"name\":\" \"}", aliceToken).statusCode());
            assertEquals(400, post(aliceClient, base + "/api/workspaces", "{\"name\":\"bad\\u0000name\"}", aliceToken).statusCode());
            String aliceName = "Alice workspace " + aliceId;
            String bobName = "Bob workspace " + bobId;
            var aliceWorkspace = post(aliceClient, base + "/api/workspaces", "{\"name\":\"" + aliceName + "\",\"createdBy\":\"" + bobId + "\"}", aliceToken);
            assertEquals(201, aliceWorkspace.statusCode());
            assertTrue(aliceWorkspace.body().contains("\"role\":\"OWNER\""));
            assertEquals(201, post(bobClient, base + "/api/workspaces", "{\"name\":\"" + bobName + "\"}", bobToken).statusCode());
            var aliceList = get(aliceClient, base + "/api/workspaces");
            var bobList = get(bobClient, base + "/api/workspaces");
            assertEquals(200, aliceList.statusCode());
            assertEquals("no-store", aliceList.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(aliceList.body().contains(aliceName));
            assertFalse(aliceList.body().contains(bobName));
            assertTrue(bobList.body().contains(bobName));
            assertFalse(bobList.body().contains(aliceName));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM workspaces w JOIN workspace_members m ON m.workspace_id=w.id WHERE w.name=? AND w.created_by=? AND m.user_id=? AND m.role='OWNER'", Integer.class, aliceName, aliceId, aliceId));
        } finally {
            jdbc.update("DELETE FROM workspaces WHERE created_by IN (?, ?)", aliceId, bobId);
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?)", aliceId, bobId);
        }
    }

    @Test
    void ownerInsertFailureRollsBackWorkspaceInsert() {
        UUID userId = UUID.randomUUID();
        String email = "rollback-" + userId + "@example.com";
        String name = "rollback-" + userId;
        try {
            users.create(userId, email, encoder.encode("test-user-password"));
            doThrow(new DataIntegrityViolationException("simulated owner insert failure")).when(workspaces).insertOwner(any(), any());
            assertThrows(DataIntegrityViolationException.class, () -> service.create(email, name));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM workspaces WHERE name = ?", Integer.class, name));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM workspace_members WHERE user_id = ?", Integer.class, userId));
        } finally {
            jdbc.update("DELETE FROM workspaces WHERE created_by = ?", userId);
            jdbc.update("DELETE FROM app_users WHERE id = ?", userId);
        }
    }

    @Test
    void membershipRulesProtectVisibilityRolesAndLastOwner() throws Exception {
        UUID ownerId = UUID.randomUUID(), editorId = UUID.randomUUID(), viewerId = UUID.randomUUID(), outsiderId = UUID.randomUUID();
        String owner = "owner-" + ownerId + "@example.com", editor = "editor-" + editorId + "@example.com";
        String viewer = "viewer-" + viewerId + "@example.com", outsider = "outside-" + outsiderId + "@example.com";
        String password = "test-user-password", base = "http://127.0.0.1:" + port;
        UUID workspaceId = null;
        try {
            users.create(ownerId, owner, encoder.encode(password));
            users.create(editorId, editor, encoder.encode(password));
            users.create(viewerId, viewer, encoder.encode(password));
            users.create(outsiderId, outsider, encoder.encode(password));
            workspaceId = service.create(owner, "membership test").id();
            String membersUrl = base + "/api/workspaces/" + workspaceId + "/members";
            HttpClient ownerClient = client(), editorClient = client(), viewerClient = client(), outsiderClient = client();
            login(ownerClient, base, owner, password); login(editorClient, base, editor, password);
            login(viewerClient, base, viewer, password); login(outsiderClient, base, outsider, password);
            String ownerCsrf = token(get(ownerClient, base + "/api/csrf").body());
            String editorCsrf = token(get(editorClient, base + "/api/csrf").body());
            String viewerCsrf = token(get(viewerClient, base + "/api/csrf").body());
            String outsiderCsrf = token(get(outsiderClient, base + "/api/csrf").body());
            assertEquals(401, get(client(), membersUrl).statusCode());
            assertEquals(404, get(outsiderClient, membersUrl).statusCode());
            assertEquals(404, post(outsiderClient, membersUrl, memberPayload(outsider, "OWNER"), outsiderCsrf).statusCode());
            assertEquals(403, post(ownerClient, membersUrl, memberPayload(editor, "EDITOR"), null).statusCode());
            assertEquals(400, post(ownerClient, membersUrl, memberPayload(editor, "SUPERUSER"), ownerCsrf).statusCode());
            assertEquals(404, post(ownerClient, membersUrl, memberPayload("missing@example.com", "VIEWER"), ownerCsrf).statusCode());
            assertEquals(200, post(ownerClient, membersUrl, memberPayload(editor.toUpperCase(java.util.Locale.ROOT), "EDITOR"), ownerCsrf).statusCode());
            assertEquals(200, post(ownerClient, membersUrl, memberPayload(viewer, "VIEWER"), ownerCsrf).statusCode());
            assertTrue(get(editorClient, membersUrl).body().contains(owner));
            assertEquals(200, get(viewerClient, membersUrl).statusCode());
            assertEquals(403, post(editorClient, membersUrl, memberPayload(editor, "OWNER"), editorCsrf).statusCode());
            assertEquals(403, delete(viewerClient, membersUrl + "/" + editorId, viewerCsrf).statusCode());
            assertEquals(409, post(ownerClient, membersUrl, memberPayload(owner, "EDITOR"), ownerCsrf).statusCode());
            assertEquals(409, delete(ownerClient, membersUrl + "/" + ownerId, ownerCsrf).statusCode());
            assertEquals(200, post(ownerClient, membersUrl, memberPayload(editor, "OWNER"), ownerCsrf).statusCode());
            assertEquals(204, delete(ownerClient, membersUrl + "/" + viewerId, ownerCsrf).statusCode());
            assertEquals(404, get(viewerClient, membersUrl).statusCode());
            assertEquals(404, delete(ownerClient, membersUrl + "/" + viewerId, ownerCsrf).statusCode());
        } finally {
            if (workspaceId != null) jdbc.update("DELETE FROM workspaces WHERE id = ?", workspaceId);
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?, ?, ?)", ownerId, editorId, viewerId, outsiderId);
        }
    }

    @Test
    void concurrentOwnerDemotionsCannotRemoveAllOwners() throws Exception {
        UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID();
        String firstEmail = "first-" + firstId + "@example.com", secondEmail = "second-" + secondId + "@example.com";
        String password = "test-user-password", base = "http://127.0.0.1:" + port;
        UUID workspaceId = null;
        try {
            users.create(firstId, firstEmail, encoder.encode(password));
            users.create(secondId, secondEmail, encoder.encode(password));
            workspaceId = service.create(firstEmail, "concurrent owners").id();
            var add = new ChangeWorkspaceMemberRequest(secondEmail, WorkspaceRole.OWNER);
            membership.setRole(workspaceId, firstEmail, add);
            HttpClient first = client(), second = client();
            login(first, base, firstEmail, password); login(second, base, secondEmail, password);
            String firstCsrf = token(get(first, base + "/api/csrf").body());
            String secondCsrf = token(get(second, base + "/api/csrf").body());
            String url = base + "/api/workspaces/" + workspaceId + "/members";
            var bothAtLock = new java.util.concurrent.CyclicBarrier(2);
            doAnswer(invocation -> {
                bothAtLock.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return invocation.callRealMethod();
            }).when(workspaces).lockWorkspace(workspaceId);
            var firstRequest = memberRequest(url, memberPayload(firstEmail, "EDITOR"), firstCsrf);
            var secondRequest = memberRequest(url, memberPayload(secondEmail, "VIEWER"), secondCsrf);
            var responses = java.util.List.of(first.sendAsync(firstRequest, HttpResponse.BodyHandlers.ofString()), second.sendAsync(secondRequest, HttpResponse.BodyHandlers.ofString()));
            assertEquals(java.util.Set.of(200, 409), responses.stream().map(java.util.concurrent.CompletableFuture::join).map(HttpResponse::statusCode).collect(java.util.stream.Collectors.toSet()));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM workspace_members WHERE workspace_id = ? AND role = 'OWNER'", Integer.class, workspaceId));
        } finally {
            if (workspaceId != null) jdbc.update("DELETE FROM workspaces WHERE id = ?", workspaceId);
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?)", firstId, secondId);
        }
    }

    @Test
    void documentCreationRequiresWorkspaceEditorAndClosesLegacyPaths() throws Exception {
        UUID ownerId = UUID.randomUUID(), editorId = UUID.randomUUID(), viewerId = UUID.randomUUID(), outsiderId = UUID.randomUUID();
        String owner = "owner-" + ownerId + "@example.com", editor = "editor-" + editorId + "@example.com";
        String viewer = "viewer-" + viewerId + "@example.com", outsider = "outsider-" + outsiderId + "@example.com";
        String password = "test-user-password", base = "http://127.0.0.1:" + port;
        UUID workspaceId = null;
        try {
            users.create(ownerId, owner, encoder.encode(password)); users.create(editorId, editor, encoder.encode(password));
            users.create(viewerId, viewer, encoder.encode(password)); users.create(outsiderId, outsider, encoder.encode(password));
            workspaceId = service.create(owner, "document scope").id();
            membership.setRole(workspaceId, owner, new ChangeWorkspaceMemberRequest(editor, WorkspaceRole.EDITOR));
            membership.setRole(workspaceId, owner, new ChangeWorkspaceMemberRequest(viewer, WorkspaceRole.VIEWER));
            HttpClient ownerClient = client(), editorClient = client(), viewerClient = client(), outsiderClient = client(), anonymous = client();
            login(ownerClient, base, owner, password); login(editorClient, base, editor, password);
            login(viewerClient, base, viewer, password); login(outsiderClient, base, outsider, password);
            String ownerCsrf = token(get(ownerClient, base + "/api/csrf").body());
            String editorCsrf = token(get(editorClient, base + "/api/csrf").body());
            String viewerCsrf = token(get(viewerClient, base + "/api/csrf").body());
            String outsiderCsrf = token(get(outsiderClient, base + "/api/csrf").body());
            String anonCsrf = token(get(anonymous, base + "/api/csrf").body());
            String url = base + "/api/workspaces/" + workspaceId + "/documents";
            assertEquals(401, post(anonymous, url, "{\"title\":\"x\"}", anonCsrf).statusCode());
            assertEquals(403, post(ownerClient, url, "{\"title\":\"x\"}", null).statusCode());
            assertEquals(403, post(viewerClient, url, "{\"title\":\"x\"}", viewerCsrf).statusCode());
            assertEquals(404, post(outsiderClient, url, "{\"title\":\"x\"}", outsiderCsrf).statusCode());
            assertEquals(404, post(ownerClient, base + "/api/workspaces/" + UUID.randomUUID() + "/documents", "{\"title\":\"x\"}", ownerCsrf).statusCode());
            assertEquals(400, post(ownerClient, url, "{\"title\":\" \"}", ownerCsrf).statusCode());
            assertEquals(400, post(ownerClient, url, "{\"title\":\"bad\\u0000title\"}", ownerCsrf).statusCode());
            assertEquals(200, get(viewerClient, url).statusCode());
            assertTrue(get(viewerClient, url).body().contains("\"items\":[]"));
            assertEquals(404, get(outsiderClient, url).statusCode());
            var created = post(ownerClient, url, "{\"title\":\"  Scoped doc  \"}", ownerCsrf);
            assertEquals(201, created.statusCode());
            assertTrue(created.body().contains(workspaceId.toString()));
            assertTrue(created.body().contains("Scoped doc"));
            assertEquals(201, post(editorClient, url, "{\"title\":\"Editor doc\"}", editorCsrf).statusCode());
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM documents WHERE workspace_id = ?", Integer.class, workspaceId));
            UUID docId = jdbc.queryForObject("SELECT id FROM documents WHERE workspace_id = ? AND title = 'Scoped doc'", UUID.class, workspaceId);
            assertEquals(200, get(ownerClient, base + "/api/documents/" + docId).statusCode());
            assertEquals(200, get(editorClient, base + "/api/documents/" + docId).statusCode());
            assertEquals(200, get(viewerClient, base + "/api/documents/" + docId).statusCode());
            assertEquals("no-store", get(viewerClient, base + "/api/documents/" + docId).headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(404, get(outsiderClient, base + "/api/documents/" + docId).statusCode());
            assertEquals(404, get(ownerClient, base + "/api/documents/" + UUID.randomUUID()).statusCode());
            assertEquals(401, get(anonymous, base + "/api/documents/" + docId).statusCode());
            jdbc.update("UPDATE documents SET created_at = ? WHERE workspace_id = ?", java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T00:00:00Z")), workspaceId);
            var firstPage = get(viewerClient, url + "?limit=1");
            assertEquals(200, firstPage.statusCode());
            String cursor = field(firstPage.body(), "nextCursor");
            String firstId = field(firstPage.body(), "id");
            var secondPage = get(viewerClient, url + "?limit=1&cursor=" + cursor);
            assertEquals(200, secondPage.statusCode());
            String secondId = field(secondPage.body(), "id");
            assertNotEquals(firstId, secondId);
            assertTrue(secondPage.body().contains("\"nextCursor\":null"));
            assertEquals(java.util.Set.of(UUID.fromString(firstId), UUID.fromString(secondId)),
                    new java.util.HashSet<>(jdbc.query("SELECT id FROM documents WHERE workspace_id=?", (rs, row) -> rs.getObject("id", UUID.class), workspaceId)));
            assertEquals(400, get(ownerClient, url + "?limit=0").statusCode());
            assertEquals(400, get(ownerClient, url + "?limit=101").statusCode());
            assertEquals(400, get(ownerClient, url + "?cursor=bad!").statusCode());
            assertEquals(400, get(ownerClient, base + "/api/workspaces/" + UUID.randomUUID() + "/documents?cursor=" + cursor).statusCode());
            membership.remove(workspaceId, owner, viewerId);
            assertEquals(404, get(viewerClient, base + "/api/documents/" + docId).statusCode());
            assertEquals(404, get(viewerClient, url).statusCode());
            assertEquals(404, post(ownerClient, base + "/api/documents", "{\"title\":\"unscoped\"}", ownerCsrf).statusCode());
        } finally {
            if (workspaceId != null) {
                jdbc.update("DELETE FROM documents WHERE workspace_id = ?", workspaceId);
                jdbc.update("DELETE FROM workspaces WHERE id = ?", workspaceId);
            }
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?, ?, ?)", ownerId, editorId, viewerId, outsiderId);
        }
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void documentCreationWaitingOnRevocationRechecksMembership() throws Exception {
        UUID ownerId = UUID.randomUUID(), editorId = UUID.randomUUID();
        String owner = "race-owner-" + ownerId + "@example.com", editor = "race-editor-" + editorId + "@example.com";
        String password = "test-user-password", base = "http://127.0.0.1:" + port;
        UUID workspaceId = null;
        var release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CompletableFuture<Void> revocation = null;
        try {
            users.create(ownerId, owner, encoder.encode(password));
            users.create(editorId, editor, encoder.encode(password));
            workspaceId = service.create(owner, "revocation race").id();
            membership.setRole(workspaceId, owner, new ChangeWorkspaceMemberRequest(editor, WorkspaceRole.EDITOR));
            HttpClient editorClient = client(); login(editorClient, base, editor, password);
            String csrf = token(get(editorClient, base + "/api/csrf").body());
            UUID targetWorkspace = workspaceId;
            var removedButUncommitted = new java.util.concurrent.CountDownLatch(1);
            revocation = java.util.concurrent.CompletableFuture.runAsync(() ->
                new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    membership.remove(targetWorkspace, owner, editorId);
                    removedButUncommitted.countDown();
                    try {
                        if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting to commit");
                    } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                }));
            assertTrue(removedButUncommitted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var request = memberRequest(base + "/api/workspaces/" + workspaceId + "/documents", "{\"title\":\"Race edit\"}", csrf);
            var creation = editorClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            assertThrows(java.util.concurrent.TimeoutException.class, () -> creation.get(250, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            revocation.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(404, creation.get(5, java.util.concurrent.TimeUnit.SECONDS).statusCode());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM documents WHERE workspace_id = ?", Integer.class, workspaceId));
        } finally {
            release.countDown();
            if (revocation != null) revocation.get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (workspaceId != null) {
                jdbc.update("DELETE FROM documents WHERE workspace_id = ?", workspaceId);
                jdbc.update("DELETE FROM workspaces WHERE id = ?", workspaceId);
            }
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?)", ownerId, editorId);
        }
    }

    @Test
    void documentMetadataRequiresPermissionAndRejectsConflicts() throws Exception {
        UUID ownerId = UUID.randomUUID(), editorId = UUID.randomUUID(), viewerId = UUID.randomUUID(), outsiderId = UUID.randomUUID();
        String owner = "meta-owner-" + ownerId + "@example.com", editor = "meta-editor-" + editorId + "@example.com";
        String viewer = "meta-viewer-" + viewerId + "@example.com", outsider = "meta-outside-" + outsiderId + "@example.com";
        String password = "test-user-password", base = "http://127.0.0.1:" + port;
        UUID workspaceId = null, outsiderWorkspaceId = null;
        try {
            users.create(ownerId, owner, encoder.encode(password)); users.create(editorId, editor, encoder.encode(password));
            users.create(viewerId, viewer, encoder.encode(password)); users.create(outsiderId, outsider, encoder.encode(password));
            workspaceId = service.create(owner, "metadata test").id();
            outsiderWorkspaceId = service.create(outsider, "other workspace").id();
            membership.setRole(workspaceId, owner, new ChangeWorkspaceMemberRequest(editor, WorkspaceRole.EDITOR));
            membership.setRole(workspaceId, owner, new ChangeWorkspaceMemberRequest(viewer, WorkspaceRole.VIEWER));
            HttpClient ownerClient = client(), editorClient = client(), viewerClient = client(), outsiderClient = client(), anonymous = client();
            login(ownerClient, base, owner, password); login(editorClient, base, editor, password);
            login(viewerClient, base, viewer, password); login(outsiderClient, base, outsider, password);
            String ownerCsrf = token(get(ownerClient, base + "/api/csrf").body());
            String editorCsrf = token(get(editorClient, base + "/api/csrf").body());
            String viewerCsrf = token(get(viewerClient, base + "/api/csrf").body());
            String outsiderCsrf = token(get(outsiderClient, base + "/api/csrf").body());
            String listUrl = base + "/api/workspaces/" + workspaceId + "/documents";
            var created = post(ownerClient, listUrl, "{\"title\":\"Original\"}", ownerCsrf);
            assertEquals(201, created.statusCode());
            assertTrue(created.body().contains("\"version\":0"));
            assertTrue(created.body().contains("\"archived\":false"));
            String id = field(created.body(), "id"), url = listUrl + "/" + id;
            for (String version : java.util.List.of("0.9", "-0.9", "0.0", "-1", "null")) {
                assertEquals(400, patch(ownerClient, url, "{\"title\":\"Invalid\",\"version\":" + version + "}", ownerCsrf).statusCode());
                assertEquals(400, post(ownerClient, url + "/archive", "{\"version\":" + version + "}", ownerCsrf).statusCode());
            }
            assertEquals(400, post(ownerClient, url + "/archive", "{}", ownerCsrf).statusCode());
            assertEquals("Original", jdbc.queryForObject("SELECT title FROM documents WHERE id = ?", String.class, UUID.fromString(id)));
            assertEquals(0L, jdbc.queryForObject("SELECT version FROM documents WHERE id = ?", Long.class, UUID.fromString(id)));
            assertFalse(jdbc.queryForObject("SELECT archived FROM documents WHERE id = ?", Boolean.class, UUID.fromString(id)));
            Document other = documentService.create(outsiderWorkspaceId, outsider, "Private");
            assertEquals(404, patch(ownerClient, listUrl + "/" + other.id(), "{\"title\":\"Cross workspace\",\"version\":0}", ownerCsrf).statusCode());
            assertEquals(404, post(ownerClient, listUrl + "/" + other.id() + "/archive", "{\"version\":0}", ownerCsrf).statusCode());
            assertEquals(403, patch(ownerClient, url, "{\"title\":\"No CSRF\",\"version\":0}", null).statusCode());
            assertEquals(403, patch(viewerClient, url, "{\"title\":\"Viewer\",\"version\":0}", viewerCsrf).statusCode());
            assertEquals(403, post(viewerClient, url + "/archive", "{\"version\":0}", viewerCsrf).statusCode());
            assertEquals(404, patch(outsiderClient, url, "{\"title\":\"Outsider\",\"version\":0}", outsiderCsrf).statusCode());
            assertEquals(404, post(outsiderClient, url + "/archive", "{\"version\":0}", outsiderCsrf).statusCode());
            String anonCsrf = token(get(anonymous, base + "/api/csrf").body());
            assertEquals(401, patch(anonymous, url, "{\"title\":\"Anonymous\",\"version\":0}", anonCsrf).statusCode());
            assertEquals(400, patch(ownerClient, url, "{\"title\":\" \",\"version\":0}", ownerCsrf).statusCode());
            assertEquals(400, patch(ownerClient, url, "{\"title\":\"Title\"}", ownerCsrf).statusCode());
            assertEquals(400, post(ownerClient, url + "/archive", "{\"version\":-1}", ownerCsrf).statusCode());
            assertEquals(404, patch(ownerClient, listUrl + "/" + UUID.randomUUID(), "{\"title\":\"Missing\",\"version\":0}", ownerCsrf).statusCode());
            var renamed = patch(editorClient, url, "{\"title\":\"  Renamed  \",\"version\":0}", editorCsrf);
            assertEquals(200, renamed.statusCode());
            assertEquals("no-store", renamed.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(renamed.body().contains("\"title\":\"Renamed\""));
            assertTrue(renamed.body().contains("\"version\":1"));
            assertEquals(409, patch(ownerClient, url, "{\"title\":\"Stale\",\"version\":0}", ownerCsrf).statusCode());
            assertEquals(409, post(ownerClient, url + "/archive", "{\"version\":0}", ownerCsrf).statusCode());
            assertTrue(get(ownerClient, base + "/api/documents/" + id).body().contains("\"title\":\"Renamed\""));
            var first = patchRequest(url, "{\"title\":\"First\",\"version\":1}", ownerCsrf);
            var second = patchRequest(url, "{\"title\":\"Second\",\"version\":1}", editorCsrf);
            var results = java.util.List.of(ownerClient.sendAsync(first, HttpResponse.BodyHandlers.ofString()), editorClient.sendAsync(second, HttpResponse.BodyHandlers.ofString()));
            assertEquals(java.util.Set.of(200, 409), results.stream().map(java.util.concurrent.CompletableFuture::join).map(HttpResponse::statusCode).collect(java.util.stream.Collectors.toSet()));
            var archived = post(ownerClient, url + "/archive", "{\"version\":2}", ownerCsrf);
            assertEquals(200, archived.statusCode());
            assertTrue(archived.body().contains("\"version\":3"));
            assertTrue(archived.body().contains("\"archived\":true"));
            assertTrue(get(viewerClient, base + "/api/documents/" + id).body().contains("\"archived\":true"));
            assertTrue(get(viewerClient, listUrl).body().contains("\"items\":[]"));
            var active = post(ownerClient, listUrl, "{\"title\":\"Active\"}", ownerCsrf);
            assertEquals(201, active.statusCode());
            String activeList = get(viewerClient, listUrl).body();
            assertTrue(activeList.contains("\"title\":\"Active\""));
            assertFalse(activeList.contains(id));
            String activeUrl = listUrl + "/" + field(active.body(), "id");
            assertEquals(200, patch(ownerClient, activeUrl, "{\"title\":\"Owner renamed\",\"version\":0}", ownerCsrf).statusCode());
            assertEquals(200, post(editorClient, activeUrl + "/archive", "{\"version\":1}", editorCsrf).statusCode());
            assertEquals(409, patch(editorClient, url, "{\"title\":\"After archive\",\"version\":3}", editorCsrf).statusCode());
            assertEquals(409, post(editorClient, url + "/archive", "{\"version\":3}", editorCsrf).statusCode());
            membership.remove(workspaceId, owner, editorId);
            assertEquals(404, post(editorClient, url + "/archive", "{\"version\":3}", editorCsrf).statusCode());
        } finally {
            if (workspaceId != null) {
                jdbc.update("DELETE FROM documents WHERE workspace_id = ?", workspaceId);
                jdbc.update("DELETE FROM workspaces WHERE id = ?", workspaceId);
            }
            if (outsiderWorkspaceId != null) {
                jdbc.update("DELETE FROM documents WHERE workspace_id = ?", outsiderWorkspaceId);
                jdbc.update("DELETE FROM workspaces WHERE id = ?", outsiderWorkspaceId);
            }
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?, ?, ?)", ownerId, editorId, viewerId, outsiderId);
        }
    }

    @Test
    void documentRenameWaitingOnRevocationRechecksMembership() throws Exception {
        UUID ownerId = UUID.randomUUID(), editorId = UUID.randomUUID(), workspaceId = null;
        String owner = "rename-owner-" + ownerId + "@example.com", editor = "rename-editor-" + editorId + "@example.com";
        String password = "test-user-password", base = "http://127.0.0.1:" + port;
        var release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CompletableFuture<Void> revocation = null;
        try {
            users.create(ownerId, owner, encoder.encode(password)); users.create(editorId, editor, encoder.encode(password));
            workspaceId = service.create(owner, "rename revocation").id();
            membership.setRole(workspaceId, owner, new ChangeWorkspaceMemberRequest(editor, WorkspaceRole.EDITOR));
            Document document = documentService.create(workspaceId, owner, "Original");
            HttpClient editorClient = client(); login(editorClient, base, editor, password);
            String csrf = token(get(editorClient, base + "/api/csrf").body());
            UUID targetWorkspace = workspaceId;
            var removed = new java.util.concurrent.CountDownLatch(1);
            revocation = java.util.concurrent.CompletableFuture.runAsync(() ->
                new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    membership.remove(targetWorkspace, owner, editorId);
                    removed.countDown();
                    try {
                        if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting to commit");
                    } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                }));
            assertTrue(removed.await(5, java.util.concurrent.TimeUnit.SECONDS));
            String url = base + "/api/workspaces/" + workspaceId + "/documents/" + document.id();
            var attemptedLock = new java.util.concurrent.CountDownLatch(1);
            doAnswer(invocation -> {
                attemptedLock.countDown();
                return invocation.callRealMethod();
            }).when(workspaces).lockWorkspace(workspaceId);
            var rename = editorClient.sendAsync(patchRequest(url, "{\"title\":\"Forbidden\",\"version\":0}", csrf), HttpResponse.BodyHandlers.ofString());
            assertTrue(attemptedLock.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> rename.get(250, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            revocation.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(404, rename.get(5, java.util.concurrent.TimeUnit.SECONDS).statusCode());
            assertEquals("Original", jdbc.queryForObject("SELECT title FROM documents WHERE id = ?", String.class, document.id()));
            assertEquals(0L, jdbc.queryForObject("SELECT version FROM documents WHERE id = ?", Long.class, document.id()));
        } finally {
            release.countDown();
            if (revocation != null) revocation.get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (workspaceId != null) {
                jdbc.update("DELETE FROM documents WHERE workspace_id = ?", workspaceId);
                jdbc.update("DELETE FROM workspaces WHERE id = ?", workspaceId);
            }
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?)", ownerId, editorId);
        }
    }

    @Test
    void apiErrorsAreConsistentAndInfrastructureDetailsStayPrivate() throws Exception {
        UUID actor = UUID.randomUUID();
        String email = "errors-" + actor + "@example.com", base = "http://127.0.0.1:" + port;
        try {
            users.create(actor, email, encoder.encode("test-user-password"));
            var anonymous = get(client(), base + "/api/workspaces");
            assertEquals(401, anonymous.statusCode());
            assertTrue(anonymous.body().contains("\"code\":\"AUTHENTICATION_REQUIRED\""));
            HttpClient session = client(); login(session, base, email, "test-user-password");
            var denied = post(session, base + "/api/workspaces", "{}", null);
            assertEquals(403, denied.statusCode());
            assertTrue(denied.body().contains("\"code\":\"ACCESS_DENIED\""));
            String csrf = token(get(session, base + "/api/csrf").body());
            var malformed = post(session, base + "/api/workspaces", "{", csrf);
            assertEquals(400, malformed.statusCode());
            assertTrue(malformed.body().contains("\"code\":\"INVALID_REQUEST\""));
            for (RuntimeException failure : java.util.List.of(
                    new org.springframework.dao.DataAccessResourceFailureException("sensitive database details"),
                    new org.springframework.dao.QueryTimeoutException("sensitive timeout details"),
                    new IllegalStateException("sensitive internal details"))) {
                doThrow(failure).when(workspaces).findByMember(actor, 21, null);
                var response = get(session, base + "/api/workspaces");
                assertEquals(failure instanceof IllegalStateException ? 500 : 503, response.statusCode());
                assertTrue(response.body().contains("\"code\":"));
                assertTrue(response.body().contains("\"message\":"));
                assertFalse(response.body().contains("sensitive"));
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            }
        } finally { jdbc.update("DELETE FROM app_users WHERE id = ?", actor); }
    }

    @Test
    void keyedCreationReplaysCommittedResultsAndRollsBackFailures() throws Exception {
        UUID actor = UUID.randomUUID(), editorId = UUID.randomUUID();
        String email = "retry-" + actor + "@example.com", editor = "retry-editor-" + editorId + "@example.com";
        try {
            users.create(actor, email, encoder.encode("test-user-password"));
            users.create(editorId, editor, encoder.encode("test-user-password"));
            String base = "http://127.0.0.1:" + port;
            HttpClient session = client(); login(session, base, email, "test-user-password");
            String csrf = token(get(session, base + "/api/csrf").body());
            var request = HttpRequest.newBuilder(URI.create(base + "/api/workspaces"))
                    .header("Content-Type", "application/json").header("X-CSRF-TOKEN", csrf).header("Idempotency-Key", "same-request")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Retry workspace\"}")).build();
            var first = session.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            var second = session.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, first.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode());
            assertEquals(201, second.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode());
            assertEquals(first.join().body(), second.join().body());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM workspaces WHERE created_by = ?", Integer.class, actor));
            UUID workspace = UUID.fromString(field(first.join().body(), "id"));
            var conflictRequest = HttpRequest.newBuilder(URI.create(base + "/api/workspaces"))
                    .header("Content-Type", "application/json").header("X-CSRF-TOKEN", csrf).header("Idempotency-Key", "same-request")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Different\"}")).build();
            var conflict = session.send(conflictRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(409, conflict.statusCode());
            assertTrue(conflict.body().contains("\"code\":\"IDEMPOTENCY_CONFLICT\""));
            assertThrows(IdempotencyConflictException.class, () -> service.create(email, "Different", "same-request"));
            membership.setRole(workspace, email, new ChangeWorkspaceMemberRequest(editor, WorkspaceRole.EDITOR));
            Document original = documentService.create(workspace, editor, "Notes", "doc-retry");
            Document replay = documentService.create(workspace, editor, "Notes", "doc-retry");
            assertEquals(original, replay); // Simulates retry after the original response was lost.
            var retryOne = java.util.concurrent.CompletableFuture.supplyAsync(() -> documentService.create(workspace, editor, "Concurrent", "concurrent-doc"));
            var retryTwo = java.util.concurrent.CompletableFuture.supplyAsync(() -> documentService.create(workspace, editor, "Concurrent", "concurrent-doc"));
            assertEquals(retryOne.get(5, java.util.concurrent.TimeUnit.SECONDS), retryTwo.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM documents WHERE workspace_id = ?", Integer.class, workspace));
            assertThrows(IdempotencyConflictException.class, () -> documentService.create(workspace, editor, "Different", "doc-retry"));
            membership.remove(workspace, email, editorId);
            assertThrows(WorkspaceResourceNotFoundException.class, () -> documentService.create(workspace, editor, "Notes", "doc-retry"));
            doThrow(new DataIntegrityViolationException("simulated rollback")).when(workspaces).insertOwner(any(), any());
            assertThrows(DataIntegrityViolationException.class, () -> service.create(email, "Failed", "rollback"));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM creation_requests WHERE user_id = ? AND request_key = 'rollback'", Integer.class, actor));
        } finally {
            jdbc.update("DELETE FROM documents WHERE workspace_id IN (SELECT id FROM workspaces WHERE created_by = ?)", actor);
            jdbc.update("DELETE FROM workspaces WHERE created_by = ?", actor);
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?)", actor, editorId);
        }
    }

    @Test
    void workspaceAndMemberListsAreBoundedAndPageWithoutDuplicates() throws Exception {
        UUID actor = UUID.randomUUID(), other = UUID.randomUUID();
        String email = "pages-" + actor + "@example.com", otherEmail = "pages-other-" + other + "@example.com";
        try {
            users.create(actor, email, encoder.encode("test-user-password"));
            users.create(other, otherEmail, encoder.encode("test-user-password"));
            UUID workspace = service.create(email, "One").id();
            service.create(email, "Two");
            service.create(otherEmail, "Private");
            membership.setRole(workspace, email, new ChangeWorkspaceMemberRequest(otherEmail, WorkspaceRole.VIEWER));
            String base = "http://127.0.0.1:" + port;
            HttpClient session = client(); login(session, base, email, "test-user-password");
            var first = get(session, base + "/api/workspaces?limit=1");
            assertEquals(200, first.statusCode());
            String cursor = first.headers().firstValue("X-Next-Cursor").orElseThrow();
            var next = get(session, base + "/api/workspaces?limit=1&after=" + cursor);
            assertEquals(200, next.statusCode());
            assertNotEquals(field(first.body(), "id"), field(next.body(), "id"));
            assertTrue(next.headers().firstValue("X-Next-Cursor").isEmpty());
            assertFalse((first.body() + next.body()).contains("Private"));
            String members = base + "/api/workspaces/" + workspace + "/members";
            var firstMember = get(session, members + "?limit=1");
            String memberCursor = firstMember.headers().firstValue("X-Next-Cursor").orElseThrow();
            var nextMember = get(session, members + "?limit=1&after=" + memberCursor);
            assertNotEquals(field(firstMember.body(), "userId"), field(nextMember.body(), "userId"));
            assertTrue(nextMember.headers().firstValue("X-Next-Cursor").isEmpty());
            assertEquals("[]", get(session, members + "?after=ffffffff-ffff-ffff-ffff-ffffffffffff").body());
            assertEquals(400, get(session, base + "/api/workspaces?limit=101").statusCode());
            assertEquals(400, get(session, members + "?limit=0").statusCode());
            assertEquals(400, get(session, members + "?after=bad").statusCode());
        } finally {
            jdbc.update("DELETE FROM workspaces WHERE created_by IN (?, ?)", actor, other);
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?)", actor, other);
        }
    }

    @Test
    void measuresWorkspaceLockContentionWhileOtherWorkspaceWritesProceed() throws Exception {
        UUID actor = UUID.randomUUID();
        String email = "locks-" + actor + "@example.com";
        var release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CompletableFuture<Void> holder = null;
        java.util.concurrent.CompletableFuture<Document> waiting = null;
        try {
            users.create(actor, email, encoder.encode("test-user-password"));
            UUID workspace = service.create(email, "Busy").id(), separate = service.create(email, "Independent").id();
            Document document = documentService.create(workspace, email, "Original");
            var held = new java.util.concurrent.CountDownLatch(1);
            holder = java.util.concurrent.CompletableFuture.runAsync(() ->
                new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    workspaces.lockWorkspace(workspace); held.countDown();
                    try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Lock measurement timed out"); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                }));
            assertTrue(held.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var attempted = new java.util.concurrent.CountDownLatch(1);
            doAnswer(invocation -> { attempted.countDown(); return invocation.callRealMethod(); }).when(workspaces).lockWorkspace(workspace);
            long started = System.nanoTime();
            waiting = java.util.concurrent.CompletableFuture.supplyAsync(() -> documentService.rename(workspace, document.id(), email, new RenameDocumentRequest("After lock", 0L)));
            assertTrue(attempted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var pending = waiting;
            assertThrows(java.util.concurrent.TimeoutException.class, () -> pending.get(250, java.util.concurrent.TimeUnit.MILLISECONDS));
            var independent = java.util.concurrent.CompletableFuture.supplyAsync(() -> documentService.create(separate, email, "Independent write"));
            assertNotNull(independent.get(5, java.util.concurrent.TimeUnit.SECONDS));
            release.countDown();
            assertEquals(1, waiting.get(5, java.util.concurrent.TimeUnit.SECONDS).version());
            holder.get(5, java.util.concurrent.TimeUnit.SECONDS);
            System.out.println("Controlled workspace lock wait (ms): " + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } finally {
            release.countDown();
            if (holder != null) holder.get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (waiting != null) waiting.get(10, java.util.concurrent.TimeUnit.SECONDS);
            jdbc.update("DELETE FROM documents WHERE workspace_id IN (SELECT id FROM workspaces WHERE created_by = ?)", actor);
            jdbc.update("DELETE FROM workspaces WHERE created_by = ?", actor);
            jdbc.update("DELETE FROM app_users WHERE id = ?", actor);
        }
    }

    @Autowired DocumentService documentService;
    @Autowired WorkspaceMembershipService membership;
    private HttpRequest patchRequest(String url, String body, String csrf) {
        return HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").header("X-CSRF-TOKEN", csrf)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private HttpResponse<String> patch(HttpClient client, String url, String body, String csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body));
        if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private String field(String json, String name) {
        var matcher = java.util.regex.Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        if (!matcher.find()) throw new IllegalStateException("Missing field " + name);
        return matcher.group(1);
    }
    private String memberPayload(String email, String role) { return "{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}"; }
    private HttpRequest memberRequest(String url, String body, String csrf) {
        return HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").header("X-CSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private HttpResponse<String> delete(HttpClient client, String url, String csrf) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).header("X-CSRF-TOKEN", csrf).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(); }
    private void login(HttpClient client, String base, String username, String password) throws Exception {
        String csrf = token(get(client, base + "/api/csrf").body());
        String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create(base + "/login")).header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-CSRF-TOKEN", csrf).POST(HttpRequest.BodyPublishers.ofString(form)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(302, response.statusCode());
        assertEquals(200, get(client, base + "/api/me").statusCode());
    }
    private String token(String json) {
        var matcher = java.util.regex.Pattern.compile("\"token\":\"([^\"]+)\"").matcher(json);
        if (!matcher.find()) throw new IllegalStateException("Missing CSRF token");
        return matcher.group(1);
    }
    private HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(HttpClient client, String url, String body, String csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
