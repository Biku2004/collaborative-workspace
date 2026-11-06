package com.example.collaborativeworkspace;

import static org.junit.jupiter.api.Assertions.*;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Full local path: WebSocket -> real Node engine -> PostgreSQL commit -> SAVED. */
@EnabledIfEnvironmentVariable(named = "WORKSPACE_RUN_YJS_ENGINE_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentUpdateEndToEndTest {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired WorkspaceService workspaces;
    @Autowired WorkspaceMembershipService membership;
    @Autowired DocumentService documents;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean YjsEngineClient engineClient;
    @Autowired DocumentUpdateService updateService;
    @Autowired DocumentCheckpointService checkpointService;
    @Autowired DocumentRevisionService revisionService;
    @Autowired tools.jackson.databind.json.JsonMapper json;
    static Process engine;

    @Test void revisionCapturesFixedHeadSurvivesLaterEditsAndChecksIntegrity() throws Exception {
        UUID ownerId = UUID.randomUUID(), outsiderId = UUID.randomUUID(), workspace = null;
        String owner = "revision-owner-" + ownerId + "@example.com", outsider = "revision-outsider-" + outsiderId + "@example.com";
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "revision-capture-test"));
        try {
            users.create(ownerId, owner, encoder.encode("test-user-password")); users.create(outsiderId, outsider, encoder.encode("test-user-password"));
            workspace = workspaces.create(owner, "revision test").id();
            UUID document = documents.create(workspace, owner, "revisions").id(), selectedWorkspace = workspace;
            var fixture = new Properties();
            try (var reader = java.nio.file.Files.newBufferedReader(Path.of("..", "experiments", "yjs-private-engine", "fixtures", "dependency-order.properties"))) { fixture.load(reader); }
            byte[] first = Base64.getDecoder().decode(fixture.getProperty("first")), next = Base64.getDecoder().decode(fixture.getProperty("dependent"));
            updateService.submit(workspace, document, ownerId, 0, UUID.randomUUID(), first, () -> true);
            var request = new CreateRevisionRequest("Before change", UUID.randomUUID());
            org.mockito.Mockito.doAnswer(invocation -> {
                if (Thread.currentThread().getName().equals("revision-capture-test")) {
                    entered.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Revision capture test timed out");
                }
                return invocation.callRealMethod();
            }).when(engineClient).reconstruct(org.mockito.ArgumentMatchers.anyList());
            var future = executor.submit(() -> revisionService.create(selectedWorkspace, document, ownerId, request, () -> true));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            updateService.submit(workspace, document, ownerId, 0, UUID.randomUUID(), next, () -> true); release.countDown();
            var saved = future.get(20, TimeUnit.SECONDS);
            assertEquals(1, saved.throughSequence());
            var preview = revisionService.preview(workspace, document, ownerId, saved.id());
            assertEquals(engineClient.reconstruct(java.util.List.of(first)).text(), engineClient.reconstruct(java.util.List.of(preview.bytes())).text());
            assertEquals(saved.id(), revisionService.create(workspace, document, ownerId, request, () -> true).id());
            assertEquals("REVISION_ID_CONFLICT", assertThrows(RevisionException.class, () -> revisionService.create(selectedWorkspace, document, ownerId,
                    new CreateRevisionRequest("Changed name", request.requestId()), () -> true)).code());
            var newer = revisionService.create(workspace, document, ownerId, new CreateRevisionRequest("After change", UUID.randomUUID()), () -> true);
            assertEquals(2, newer.throughSequence());
            var page = revisionService.list(workspace, document, ownerId, 1, null);
            assertEquals(newer.id(), page.items().get(0).id()); assertNotNull(page.nextCursor());
            assertEquals(saved.id(), revisionService.list(workspace, document, ownerId, 1, page.nextCursor()).items().get(0).id());
            assertEquals("NOT_FOUND", assertThrows(RevisionException.class, () -> revisionService.preview(selectedWorkspace, document, outsiderId, saved.id())).code());
            assertEquals("NOT_FOUND", assertThrows(RevisionException.class, () -> revisionService.list(selectedWorkspace, document, outsiderId, 20, null)).code());
            byte[] corrupt = preview.bytes(); corrupt[corrupt.length - 1] ^= 1;
            jdbc.update("UPDATE document_revisions SET checkpoint=? WHERE id=?", corrupt, saved.id());
            assertEquals("REVISION_INVALID", assertThrows(RevisionException.class, () -> revisionService.preview(selectedWorkspace, document, ownerId, saved.id())).code());
        } finally {
            release.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS);
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", workspace); jdbc.update("DELETE FROM workspaces WHERE id=?", workspace); }
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?)", ownerId, outsiderId);
        }
    }

    @Test void checkpointRetainsConcurrentTailAndRejectsCorruption() throws Exception {
        UUID ownerId = UUID.randomUUID(), workspace = null;
        String owner = "checkpoint-" + ownerId + "@example.com";
        var release = new java.util.concurrent.CountDownLatch(1);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "checkpoint-test"));
        try {
            users.create(ownerId, owner, encoder.encode("test-user-password"));
            workspace = workspaces.create(owner, "checkpoint test").id();
            UUID document = documents.create(workspace, owner, "checkpoint").id();
            var fixture = new Properties();
            try (var reader = java.nio.file.Files.newBufferedReader(Path.of("..", "experiments", "yjs-private-engine", "fixtures", "dependency-order.properties"))) { fixture.load(reader); }
            byte[] first = Base64.getDecoder().decode(fixture.getProperty("first"));
            byte[] next = Base64.getDecoder().decode(fixture.getProperty("dependent"));
            updateService.submit(workspace, document, ownerId, 0, UUID.randomUUID(), first, () -> true);
            org.mockito.Mockito.doAnswer(invocation -> {
                if (Thread.currentThread().getName().equals("checkpoint-test")) {
                    entered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Checkpoint test timed out");
                }
                return invocation.callRealMethod();
            }).when(engineClient).reconstruct(org.mockito.ArgumentMatchers.anyList());
            UUID selectedWorkspace = workspace;
            var future = executor.submit(() -> checkpointService.create(selectedWorkspace, document, ownerId));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            updateService.submit(workspace, document, ownerId, 0, UUID.randomUUID(), next, () -> true);
            release.countDown();
            var published = future.get(20, TimeUnit.SECONDS);
            assertEquals(1, published.checkpoint().sequence());
            assertEquals(1, published.observedHead());
            assertEquals(2L, jdbc.queryForObject("SELECT head_sequence FROM document_streams WHERE document_id=?", Long.class, document));
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Integer.class, document));
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM document_update_outbox WHERE document_id=?", Integer.class, document));
            assertEquals(engineClient.reconstruct(java.util.List.of(first, next)).text(), engineClient.reconstruct(java.util.List.of(published.checkpoint().bytes(), next)).text());
            var advanced = checkpointService.create(workspace, document, ownerId);
            assertEquals(2, advanced.checkpoint().sequence());
            assertEquals(2, checkpointService.latest(workspace, document, ownerId).sequence());
            byte[] corrupted = advanced.checkpoint().bytes(); corrupted[corrupted.length - 1] ^= 1;
            jdbc.update("UPDATE document_checkpoints SET checkpoint=? WHERE document_id=? AND epoch=0 AND through_sequence=2", corrupted, document);
            assertEquals("CHECKPOINT_CORRUPT", assertThrows(CheckpointException.class, () -> checkpointService.latest(selectedWorkspace, document, ownerId)).code());
            assertEquals("CHECKPOINT_CORRUPT", assertThrows(CheckpointException.class, () -> checkpointService.create(selectedWorkspace, document, ownerId)).code());
        } finally {
            release.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS);
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", workspace); jdbc.update("DELETE FROM workspaces WHERE id=?", workspace); }
            jdbc.update("DELETE FROM app_users WHERE id=?", ownerId);
        }
    }

    @Test void checkpointJobsAdvanceInBoundedBatchesAndRejectDamagedLog() throws Exception {
        UUID ownerId = UUID.randomUUID(), workspace = null;
        String owner = "checkpoint-batches-" + ownerId + "@example.com";
        try {
            users.create(ownerId, owner, encoder.encode("test-user-password"));
            workspace = workspaces.create(owner, "checkpoint batches").id();
            UUID document = documents.create(workspace, owner, "long log").id();
            var fixture = new Properties();
            try (var reader = java.nio.file.Files.newBufferedReader(Path.of("..", "experiments", "yjs-private-engine", "fixtures", "dependency-order.properties"))) { fixture.load(reader); }
            byte[] first = Base64.getDecoder().decode(fixture.getProperty("first"));
            for (int i = 0; i < 18; i++) updateService.submit(workspace, document, ownerId, 0, UUID.randomUUID(), first, () -> true);
            var firstBatch = checkpointService.create(workspace, document, ownerId);
            assertEquals(16, firstBatch.checkpoint().sequence()); assertEquals(18, firstBatch.observedHead());
            var secondBatch = checkpointService.create(workspace, document, ownerId);
            assertEquals(18, secondBatch.checkpoint().sequence());
            assertEquals(18, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Integer.class, document));
            assertArrayEquals(jdbc.queryForObject("SELECT checkpoint FROM document_streams WHERE document_id=?", byte[].class, document), secondBatch.checkpoint().bytes());
            UUID broken = documents.create(workspace, owner, "damaged log").id();
            updateService.submit(workspace, broken, ownerId, 0, UUID.randomUUID(), first, () -> true);
            byte[] changed = first.clone(); changed[changed.length - 1] ^= 1;
            jdbc.update("UPDATE document_updates SET payload=? WHERE document_id=? AND sequence=1", changed, broken);
            UUID selectedWorkspace = workspace;
            assertEquals("CHECKPOINT_LOG_CORRUPT", assertThrows(CheckpointException.class, () -> checkpointService.create(selectedWorkspace, broken, ownerId)).code());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document_checkpoints WHERE document_id=?", Integer.class, broken));
            UUID gap = documents.create(workspace, owner, "missing update").id();
            updateService.submit(workspace, gap, ownerId, 0, UUID.randomUUID(), first, () -> true);
            updateService.submit(workspace, gap, ownerId, 0, UUID.randomUUID(), first, () -> true);
            jdbc.update("DELETE FROM document_updates WHERE document_id=? AND epoch=0 AND sequence=1", gap);
            assertEquals("CHECKPOINT_LOG_CORRUPT", assertThrows(CheckpointException.class, () -> checkpointService.create(selectedWorkspace, gap, ownerId)).code());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document_checkpoints WHERE document_id=?", Integer.class, gap));
        } finally {
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", workspace); jdbc.update("DELETE FROM workspaces WHERE id=?", workspace); }
            jdbc.update("DELETE FROM app_users WHERE id=?", ownerId);
        }
    }

    @Test void checkpointRechecksRoleAfterReconstruction() throws Exception {
        UUID ownerId = UUID.randomUUID(), editorId = UUID.randomUUID(), workspace = null;
        String owner = "checkpoint-role-owner-" + ownerId + "@example.com", editor = "checkpoint-role-editor-" + editorId + "@example.com";
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "checkpoint-role-test"));
        try {
            users.create(ownerId, owner, encoder.encode("test-user-password")); users.create(editorId, editor, encoder.encode("test-user-password"));
            workspace = workspaces.create(owner, "checkpoint role").id();
            membership.setRole(workspace, owner, new ChangeWorkspaceMemberRequest(editor, WorkspaceRole.EDITOR));
            UUID document = documents.create(workspace, owner, "role race").id();
            org.mockito.Mockito.doAnswer(invocation -> {
                if (Thread.currentThread().getName().equals("checkpoint-role-test")) {
                    entered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Checkpoint role test timed out");
                }
                return invocation.callRealMethod();
            }).when(engineClient).reconstruct(org.mockito.ArgumentMatchers.anyList());
            UUID selectedWorkspace = workspace;
            var future = executor.submit(() -> checkpointService.create(selectedWorkspace, document, editorId));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            membership.setRole(workspace, owner, new ChangeWorkspaceMemberRequest(editor, WorkspaceRole.VIEWER));
            release.countDown();
            var failed = assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(20, TimeUnit.SECONDS));
            assertInstanceOf(CheckpointException.class, failed.getCause());
            assertEquals("ACCESS_DENIED", ((CheckpointException) failed.getCause()).code());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document_checkpoints WHERE document_id=?", Integer.class, document));
        } finally {
            release.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS);
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", workspace); jdbc.update("DELETE FROM workspaces WHERE id=?", workspace); }
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?)", ownerId, editorId);
        }
    }

    @Test void concurrentCheckpointJobsDoNotOverwriteTheSameBoundary() throws Exception {
        UUID ownerId = UUID.randomUUID(), workspace = null;
        String owner = "checkpoint-concurrent-" + ownerId + "@example.com";
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "checkpoint-busy-test"));
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        try {
            users.create(ownerId, owner, encoder.encode("test-user-password"));
            workspace = workspaces.create(owner, "checkpoint concurrent").id();
            UUID document = documents.create(workspace, owner, "empty").id(), selectedWorkspace = workspace;
            org.mockito.Mockito.doAnswer(invocation -> {
                if (Thread.currentThread().getName().equals("checkpoint-busy-test")) {
                    entered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Checkpoint busy test timed out");
                }
                return invocation.callRealMethod();
            }).when(engineClient).reconstruct(org.mockito.ArgumentMatchers.anyList());
            var a = executor.submit(() -> checkpointService.create(selectedWorkspace, document, ownerId));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertEquals("CHECKPOINT_BUSY", assertThrows(CheckpointException.class, () -> checkpointService.create(selectedWorkspace, document, ownerId)).code());
            assertEquals("REVISION_BUSY", assertThrows(RevisionException.class, () -> revisionService.create(selectedWorkspace, document, ownerId,
                    new CreateRevisionRequest("Busy", UUID.randomUUID()), () -> true)).code());
            release.countDown();
            var first = a.get(20, TimeUnit.SECONDS);
            var second = checkpointService.create(selectedWorkspace, document, ownerId);
            assertEquals(0, first.checkpoint().sequence());
            assertArrayEquals(first.checkpoint().bytes(), second.checkpoint().bytes());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_checkpoints WHERE document_id=?", Integer.class, document));
        } finally {
            release.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS);
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", workspace); jdbc.update("DELETE FROM workspaces WHERE id=?", workspace); }
            jdbc.update("DELETE FROM app_users WHERE id=?", ownerId);
        }
    }

    @Test void checkpointDoesNotPublishAfterEpochChanges() throws Exception {
        UUID ownerId = UUID.randomUUID(), workspace = null;
        String owner = "checkpoint-epoch-" + ownerId + "@example.com";
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "checkpoint-epoch-test"));
        try {
            users.create(ownerId, owner, encoder.encode("test-user-password"));
            workspace = workspaces.create(owner, "checkpoint epoch").id();
            UUID document = documents.create(workspace, owner, "epoch race").id();
            org.mockito.Mockito.doAnswer(invocation -> {
                if (Thread.currentThread().getName().equals("checkpoint-epoch-test")) {
                    entered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Checkpoint epoch test timed out");
                }
                return invocation.callRealMethod();
            }).when(engineClient).reconstruct(org.mockito.ArgumentMatchers.anyList());
            UUID selectedWorkspace = workspace;
            var future = executor.submit(() -> checkpointService.create(selectedWorkspace, document, ownerId));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            jdbc.update("INSERT INTO document_streams(document_id,epoch,head_sequence,checkpoint) VALUES (?,1,0,?)", document, new byte[] {0, 0});
            release.countDown();
            var failed = assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(20, TimeUnit.SECONDS));
            assertInstanceOf(CheckpointException.class, failed.getCause());
            assertEquals("EPOCH_CHANGED", ((CheckpointException) failed.getCause()).code());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document_checkpoints WHERE document_id=?", Integer.class, document));
        } finally {
            release.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS);
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", workspace); jdbc.update("DELETE FROM workspaces WHERE id=?", workspace); }
            jdbc.update("DELETE FROM app_users WHERE id=?", ownerId);
        }
    }

    @Test void checkpointRechecksSessionBeforePublishing() throws Exception {
        UUID ownerId = UUID.randomUUID(), workspace = null;
        String owner = "checkpoint-session-" + ownerId + "@example.com";
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var valid = new java.util.concurrent.atomic.AtomicBoolean(true);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "checkpoint-session-test"));
        try {
            users.create(ownerId, owner, encoder.encode("test-user-password"));
            workspace = workspaces.create(owner, "checkpoint session").id();
            UUID document = documents.create(workspace, owner, "session race").id();
            org.mockito.Mockito.doAnswer(invocation -> {
                if (Thread.currentThread().getName().equals("checkpoint-session-test")) {
                    entered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Checkpoint session test timed out");
                }
                return invocation.callRealMethod();
            }).when(engineClient).reconstruct(org.mockito.ArgumentMatchers.anyList());
            UUID selectedWorkspace = workspace;
            var future = executor.submit(() -> checkpointService.create(selectedWorkspace, document, ownerId, valid::get));
            assertTrue(entered.await(10, TimeUnit.SECONDS)); valid.set(false); release.countDown();
            var failed = assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(20, TimeUnit.SECONDS));
            assertInstanceOf(CheckpointException.class, failed.getCause());
            assertEquals("ACCESS_DENIED", ((CheckpointException) failed.getCause()).code());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document_checkpoints WHERE document_id=?", Integer.class, document));
        } finally {
            release.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS);
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", workspace); jdbc.update("DELETE FROM workspaces WHERE id=?", workspace); }
            jdbc.update("DELETE FROM app_users WHERE id=?", ownerId);
        }
    }

    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry) throws Exception {
        String database = TestDatabaseGuard.verifiedUrl();
        int enginePort; try (var socket = new ServerSocket(0)) { enginePort = socket.getLocalPort(); }
        Path directory = Path.of("..", "experiments", "yjs-private-engine").toAbsolutePath().normalize();
        var builder = new ProcessBuilder("node", "dist/src/server.js").directory(directory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("YJS_ENGINE_PORT", Integer.toString(enginePort));
        engine = builder.start();
        var probe = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        boolean ready = false;
        while (System.nanoTime() < deadline && engine.isAlive()) {
            try {
                if (probe.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + enginePort + "/")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 404) { ready = true; break; }
            } catch (java.io.IOException ignored) { }
            Thread.sleep(50);
        }
        if (!ready) { engine.destroyForcibly(); throw new IllegalStateException("Private Yjs engine did not start; build it first"); }
        registry.add("workspace.yjs-engine-url", () -> "http://127.0.0.1:" + enginePort);
        registry.add("workspace.websocket.allowed-origin", () -> "http://127.0.0.1:5173");
        registry.add("workspace.delivery.poll-ms", () -> "100");
        registry.add("workspace.rich.enabled", () -> "true");
        registry.add("spring.datasource.url", () -> database);
        registry.add("spring.datasource.username", () -> System.getenv("WORKSPACE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("WORKSPACE_TEST_DB_PASSWORD"));
        registry.add("workspace.dev.password", () -> "test-only-password");
    }

    @Test void richDocumentNegotiatesReplaysAndCommitsThroughTheRealEngine() throws Exception {
        UUID ownerId = UUID.randomUUID(), viewerId = UUID.randomUUID(), workspace = null;
        String owner = "rich-e2e-" + ownerId + "@example.com", viewer = "rich-view-" + viewerId + "@example.com";
        String base = "http://127.0.0.1:" + port;
        WebSocket socket = null, viewerSocket = null;
        try {
            users.create(ownerId, owner, encoder.encode("test-user-password"));
            users.create(viewerId, viewer, encoder.encode("test-user-password"));
            workspace = workspaces.create(owner, "rich engine").id();
            membership.setRole(workspace, owner, new ChangeWorkspaceMemberRequest(viewer, WorkspaceRole.VIEWER));
            UUID document = documents.create(workspace, owner, "Rich notes", "rich-e2e", "RICH_TEXT_V1").id();
            var fixture = new Properties();
            try (var reader = java.nio.file.Files.newBufferedReader(Path.of("..", "experiments", "yjs-private-engine", "fixtures", "rich-dependent.properties"))) { fixture.load(reader); }
            var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            login(client, base, owner);
            var listener = new Listener();
            socket = client.newWebSocketBuilder().header("Origin", "http://127.0.0.1:5173")
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/workspaces/" + workspace + "/socket"), listener).join();
            socket.sendText("{\"type\":\"HELLO\",\"version\":1}", true).join(); listener.next("WELCOME");
            socket.sendText("{\"type\":\"JOIN\",\"version\":1,\"documentId\":\"" + document + "\"}", true).join();
            assertTrue(listener.next("REJECTED").contains("SCHEMA_UNSUPPORTED"));
            socket.sendText("{\"type\":\"JOIN\",\"version\":1,\"documentId\":\"" + document + "\",\"format\":\"RICH_TEXT_V1\"}", true).join();
            assertTrue(listener.next("JOINED").contains("RICH_TEXT_V1"));
            socket.sendText("{\"type\":\"SYNC\",\"version\":1,\"epoch\":0,\"after\":0}", true).join();
            var initial = json.readTree(listener.next("SYNC_PAGE"));
            assertEquals(1, initial.path("updates").size());
            assertEquals(fixture.getProperty("initial"), initial.path("updates").get(0).path("payload").asText());
            UUID updateId = UUID.randomUUID();
            String request = "{\"type\":\"SUBMIT_UPDATE\",\"version\":1,\"epoch\":0,\"updateId\":\"" + updateId + "\",\"payload\":\"" + fixture.getProperty("update") + "\"}";
            socket.sendText(request, true).join();
            String saved = listener.next("SAVED");
            assertTrue(saved.contains(updateId.toString())); assertTrue(saved.contains("\"sequence\":2"));
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Integer.class, document));
            byte[] checkpoint = jdbc.queryForObject("SELECT checkpoint FROM document_streams WHERE document_id=?", byte[].class, document);
            assertTrue(engineClient.reconstructRich(java.util.List.of(checkpoint)).initialized());
            var materialized = checkpointService.create(workspace, document, ownerId);
            assertEquals(2, materialized.checkpoint().sequence());
            assertTrue(engineClient.reconstructRich(java.util.List.of(materialized.checkpoint().bytes())).initialized());
            var richRevision = revisionService.create(workspace, document, ownerId, new CreateRevisionRequest("Rich state", UUID.randomUUID()), () -> true);
            assertEquals(2, richRevision.throughSequence());
            assertArrayEquals(checkpoint, revisionService.preview(workspace, document, ownerId, richRevision.id()).bytes());
            socket.sendText(request, true).join();
            assertTrue(listener.next("SAVED").contains("\"duplicate\":true"));
            var viewerClient = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            login(viewerClient, base, viewer);
            var viewerListener = new Listener();
            viewerSocket = viewerClient.newWebSocketBuilder().header("Origin", "http://127.0.0.1:5173")
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/workspaces/" + workspace + "/socket"), viewerListener).join();
            viewerSocket.sendText("{\"type\":\"HELLO\",\"version\":1}", true).join(); viewerListener.next("WELCOME");
            viewerSocket.sendText("{\"type\":\"JOIN\",\"version\":1,\"documentId\":\"" + document + "\",\"format\":\"RICH_TEXT_V1\"}", true).join(); viewerListener.next("JOINED");
            viewerSocket.sendText(request.replace(updateId.toString(), UUID.randomUUID().toString()), true).join();
            assertTrue(viewerListener.next("REJECTED").contains("ACCESS_DENIED"));
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Integer.class, document));
        } finally {
            if (socket != null) socket.abort();
            if (viewerSocket != null) viewerSocket.abort();
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", workspace); jdbc.update("DELETE FROM workspaces WHERE id=?", workspace); }
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?)", ownerId, viewerId);
        }
    }
    @AfterAll static void stopEngine() throws Exception {
        if (engine != null) { engine.destroy(); if (!engine.waitFor(5, TimeUnit.SECONDS)) engine.destroyForcibly(); }
    }

    @Test void checkpointHttpUsesCurrentRolesAndNoStore() throws Exception {
        UUID ownerId = UUID.randomUUID(), viewerId = UUID.randomUUID(), outsiderId = UUID.randomUUID(), workspace = null;
        String owner = "checkpoint-http-owner-" + ownerId + "@example.com", viewer = "checkpoint-http-view-" + viewerId + "@example.com";
        String outsider = "checkpoint-http-out-" + outsiderId + "@example.com", base = "http://127.0.0.1:" + port;
        try {
            users.create(ownerId, owner, encoder.encode("test-user-password")); users.create(viewerId, viewer, encoder.encode("test-user-password"));
            users.create(outsiderId, outsider, encoder.encode("test-user-password"));
            workspace = workspaces.create(owner, "checkpoint http").id();
            membership.setRole(workspace, owner, new ChangeWorkspaceMemberRequest(viewer, WorkspaceRole.VIEWER));
            UUID document = documents.create(workspace, owner, "empty").id();
            String path = base + "/api/workspaces/" + workspace + "/documents/" + document + "/checkpoints";
            var ownerClient = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            var viewerClient = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            var outsiderClient = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            login(ownerClient, base, owner); login(viewerClient, base, viewer); login(outsiderClient, base, outsider);
            assertEquals(404, get(ownerClient, path + "/latest").statusCode());
            assertEquals(404, get(outsiderClient, path + "/latest").statusCode());
            String ownerToken = json.readTree(get(ownerClient, base + "/api/csrf").body()).path("token").asText();
            String viewerToken = json.readTree(get(viewerClient, base + "/api/csrf").body()).path("token").asText();
            String revisionsPath = base + "/api/workspaces/" + workspace + "/documents/" + document + "/revisions";
            String revisionJson = json.writeValueAsString(java.util.Map.of("name", "Reviewed", "requestId", UUID.randomUUID()));
            var revisionPost = HttpRequest.newBuilder(URI.create(revisionsPath)).header("Content-Type", "application/json")
                    .header("X-CSRF-TOKEN", ownerToken).POST(HttpRequest.BodyPublishers.ofString(revisionJson)).build();
            var revisionCreated = ownerClient.send(revisionPost, HttpResponse.BodyHandlers.ofString()); assertEquals(201, revisionCreated.statusCode());
            String revisionId = json.readTree(revisionCreated.body()).path("id").asText();
            var viewerPost = HttpRequest.newBuilder(URI.create(revisionsPath)).header("Content-Type", "application/json")
                    .header("X-CSRF-TOKEN", viewerToken).POST(HttpRequest.BodyPublishers.ofString(revisionJson)).build();
            assertEquals(403, viewerClient.send(viewerPost, HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(200, get(viewerClient, revisionsPath).statusCode());
            assertEquals(200, get(viewerClient, revisionsPath + "/" + revisionId).statusCode());
            assertTrue(get(viewerClient, revisionsPath + "/" + revisionId).headers().firstValue("Cache-Control").orElse("").contains("no-store"));
            assertEquals(404, get(outsiderClient, revisionsPath).statusCode()); assertEquals(404, get(outsiderClient, revisionsPath + "/" + revisionId).statusCode());
            assertEquals(400, get(ownerClient, revisionsPath + "?limit=101").statusCode());
            var noRevisionCsrf = HttpRequest.newBuilder(URI.create(revisionsPath)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(revisionJson)).build();
            assertEquals(403, ownerClient.send(noRevisionCsrf, HttpResponse.BodyHandlers.discarding()).statusCode());
            var invalidName = HttpRequest.newBuilder(URI.create(revisionsPath)).header("Content-Type", "application/json").header("X-CSRF-TOKEN", ownerToken)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(java.util.Map.of("name", " ", "requestId", UUID.randomUUID())))).build();
            assertEquals(400, ownerClient.send(invalidName, HttpResponse.BodyHandlers.discarding()).statusCode());
            UUID another = documents.create(workspace, owner, "another").id();
            assertEquals(404, get(ownerClient, base + "/api/workspaces/" + workspace + "/documents/" + another + "/revisions/" + revisionId).statusCode());
            var withoutCsrf = HttpRequest.newBuilder(URI.create(path)).POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(403, ownerClient.send(withoutCsrf, HttpResponse.BodyHandlers.discarding()).statusCode());
            var denied = HttpRequest.newBuilder(URI.create(path)).header("X-CSRF-TOKEN", viewerToken).POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(403, viewerClient.send(denied, HttpResponse.BodyHandlers.discarding()).statusCode());
            var create = HttpRequest.newBuilder(URI.create(path)).header("X-CSRF-TOKEN", ownerToken).POST(HttpRequest.BodyPublishers.noBody()).build();
            var created = ownerClient.send(create, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, created.statusCode()); assertEquals(0, json.readTree(created.body()).path("throughSequence").longValue());
            var retry = ownerClient.send(create, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, retry.statusCode()); assertEquals(json.readTree(created.body()).path("sha256"), json.readTree(retry.body()).path("sha256"));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_checkpoints WHERE document_id=?", Integer.class, document));
            var read = get(viewerClient, path + "/latest");
            assertEquals(200, read.statusCode()); assertTrue(read.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
            assertEquals("PLAIN_TEXT_V1", json.readTree(read.body()).path("format").asText());
            assertEquals(404, get(outsiderClient, path + "/latest").statusCode());
        } finally {
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", workspace); jdbc.update("DELETE FROM workspaces WHERE id=?", workspace); }
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?, ?)", ownerId, viewerId, outsiderId);
        }
    }

    @Test void actualEngineAndDatabaseProduceSavedOnlyAfterCommit() throws Exception {
        UUID user = UUID.randomUUID(), workspace = null;
        String email = "e2e-" + user + "@example.com", base = "http://127.0.0.1:" + port;
        WebSocket socket = null, readerSocket = null;
        try {
            users.create(user, email, encoder.encode("test-user-password"));
            workspace = workspaces.create(email, "real engine").id();
            UUID document = documents.create(workspace, email, "real engine doc").id();
            var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            login(client, base, email);
            var listener = new Listener();
            socket = client.newWebSocketBuilder().header("Origin", "http://127.0.0.1:5173")
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/workspaces/" + workspace + "/socket"), listener).join();
            socket.sendText("{\"type\":\"HELLO\",\"version\":1}", true).join();
            assertTrue(listener.next("WELCOME").contains("WELCOME"));
            socket.sendText("{\"type\":\"JOIN\",\"version\":1,\"documentId\":\"" + document + "\"}", true).join();
            assertTrue(listener.next("JOINED").contains("JOINED"));
            var readerClient = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            login(readerClient, base, email);
            var readerListener = new Listener();
            readerSocket = readerClient.newWebSocketBuilder().header("Origin", "http://127.0.0.1:5173")
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/workspaces/" + workspace + "/socket"), readerListener).join();
            readerSocket.sendText("{\"type\":\"HELLO\",\"version\":1}", true).join(); readerListener.next("WELCOME");
            readerSocket.sendText("{\"type\":\"JOIN\",\"version\":1,\"documentId\":\"" + document + "\"}", true).join(); readerListener.next("JOINED");
            var fixture = new Properties();
            try (var reader = java.nio.file.Files.newBufferedReader(Path.of("..", "experiments", "yjs-private-engine", "fixtures", "dependency-order.properties"))) { fixture.load(reader); }
            String missing = "{\"type\":\"SUBMIT_UPDATE\",\"version\":1,\"epoch\":0,\"updateId\":\"" + UUID.randomUUID() + "\",\"payload\":\"" + fixture.getProperty("dependent") + "\"}";
            socket.sendText(missing, true).join();
            assertTrue(listener.next("REJECTED").contains("\"code\":\"INVALID_UPDATE\""));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Integer.class, document));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document_streams WHERE document_id=?", Integer.class, document));
            String previous = null;
            for (String name : new String[] { "first", "dependent", "deletion" }) {
                String payload = fixture.getProperty(name);
                String id = UUID.randomUUID().toString();
                String request = "{\"type\":\"SUBMIT_UPDATE\",\"version\":1,\"epoch\":0,\"updateId\":\"" + id + "\",\"payload\":\"" + payload + "\"}";
                socket.sendText(request, true).join();
                String saved = listener.next("SAVED");
                assertTrue(saved.contains("\"type\":\"SAVED\""));
                assertTrue(saved.contains(id));
                previous = request;
            }
            assertEquals(3L, jdbc.queryForObject("SELECT head_sequence FROM document_streams WHERE document_id=?", Long.class, document));
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM document_update_outbox WHERE document_id=?", Integer.class, document));
            byte[] checkpoint = jdbc.queryForObject("SELECT checkpoint FROM document_streams WHERE document_id=?", byte[].class, document);
            assertEquals("ac!", engineClient.reconstruct(java.util.List.of(checkpoint)).text());
            var deletionCheckpoint = checkpointService.create(workspace, document, user);
            assertEquals(3, deletionCheckpoint.checkpoint().sequence());
            assertEquals("ac!", engineClient.reconstruct(java.util.List.of(deletionCheckpoint.checkpoint().bytes())).text());
            var received = new java.util.TreeMap<Long, byte[]>();
            for (int i = 0; i < 3; i++) {
                var event = json.readTree(readerListener.next("UPDATE"));
                received.put(event.path("sequence").longValue(), Base64.getDecoder().decode(event.path("payload").asText()));
            }
            assertEquals(java.util.Set.of(1L, 2L, 3L), received.keySet());
            assertEquals("ac!", engineClient.reconstruct(new java.util.ArrayList<>(received.values())).text());
            socket.sendText(previous, true).join();
            assertTrue(listener.next("SAVED").contains("\"duplicate\":true"));
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Integer.class, document));
            assertArrayEquals(checkpoint, Base64.getDecoder().decode(Base64.getEncoder().encodeToString(checkpoint)));
        } finally {
            if (socket != null) socket.abort();
            if (readerSocket != null) readerSocket.abort();
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", workspace); jdbc.update("DELETE FROM workspaces WHERE id=?", workspace); }
            jdbc.update("DELETE FROM app_users WHERE id=?", user);
        }
    }
    private HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private void login(HttpClient client, String base, String email) throws Exception {
        String csrfBody = get(client, base + "/api/csrf").body();
        var matcher = java.util.regex.Pattern.compile("\"token\":\"([^\"]+)\"").matcher(csrfBody);
        assertTrue(matcher.find());
        String form = "username=" + URLEncoder.encode(email, StandardCharsets.UTF_8) + "&password=test-user-password";
        var login = HttpRequest.newBuilder(URI.create(base + "/login")).header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-CSRF-TOKEN", matcher.group(1)).POST(HttpRequest.BodyPublishers.ofString(form)).build();
        assertEquals(302, client.send(login, HttpResponse.BodyHandlers.discarding()).statusCode());
    }
    private static final class Listener implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Throwable> error = new CompletableFuture<>();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) { messages.add(data.toString()); socket.request(1); return null; }
        @Override public void onError(WebSocket socket, Throwable cause) { error.complete(cause); }
        String next(String type) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            while (System.nanoTime() < deadline) {
                String value = messages.poll(1, TimeUnit.SECONDS);
                if (value == null && error.isDone()) throw new AssertionError("Socket failed", error.join());
                if (value != null && value.contains("\"type\":\"" + type + "\"")) return value;
            }
            throw new AssertionError("Timed out waiting for " + type);
        }
    }
}
