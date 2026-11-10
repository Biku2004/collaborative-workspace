package com.example.collaborativeworkspace.collaboration.transport;

import com.example.collaborativeworkspace.auth.UserRepository;
import com.example.collaborativeworkspace.collaboration.engine.YjsEngineClient;
import com.example.collaborativeworkspace.collaboration.engine.YjsEngineException;
import com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateException;
import com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateRepository;
import com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateService;
import com.example.collaborativeworkspace.documents.DocumentService;
import com.example.collaborativeworkspace.support.TestDatabaseGuard;
import com.example.collaborativeworkspace.workspaces.ChangeWorkspaceMemberRequest;
import com.example.collaborativeworkspace.workspaces.WorkspaceMembershipService;
import com.example.collaborativeworkspace.workspaces.WorkspaceRole;
import com.example.collaborativeworkspace.workspaces.WorkspaceService;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@EnabledIfEnvironmentVariable(named = "WORKSPACE_RUN_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(WorkspaceSocketIntegrationTest.TestEndpoints.class)
class WorkspaceSocketIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired WorkspaceService workspaces;
    @Autowired PasswordEncoder encoder;
    @Autowired SocketSessionRegistry registry;
    @Autowired DocumentService documents;
    @Autowired WorkspaceMembershipService membership;
    @Autowired DocumentRoomHub rooms;
    @Autowired DocumentUpdateService updateService;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoSpyBean DocumentUpdateRepository updateRepository;
    @MockitoSpyBean YjsEngineClient engine;
    private static final String ORIGIN = "http://127.0.0.1:5173";

    @Test void committedPrivilegeReductionClosesIdleAndJoinedPeersWithoutRevokingOtherWorkspaces() throws Exception {
        UUID ownerId = UUID.randomUUID(), memberId = UUID.randomUUID(), workspace = null, otherWorkspace = null;
        String owner = "active-owner-" + ownerId + "@example.com", member = "active-member-" + memberId + "@example.com";
        String base = "http://127.0.0.1:" + port; var sockets = new java.util.ArrayList<WebSocket>();
        try {
            users.create(ownerId, owner, encoder.encode("test-user-password")); users.create(memberId, member, encoder.encode("test-user-password"));
            workspace = workspaces.create(owner, "Active revocation").id(); otherWorkspace = workspaces.create(owner, "Other access").id();
            membership.setRole(workspace, owner, new ChangeWorkspaceMemberRequest(member, WorkspaceRole.EDITOR));
            membership.setRole(otherWorkspace, owner, new ChangeWorkspaceMemberRequest(member, WorkspaceRole.EDITOR));
            UUID document = documents.create(workspace, owner, "Shared").id(), otherDocument = documents.create(otherWorkspace, owner, "Other").id();
            var client = client(); login(client, base, member);
            var joined = new QueueListener(); var idle = new QueueListener(); var other = new QueueListener();
            URI uri = URI.create("ws://127.0.0.1:" + port + "/api/workspaces/" + workspace + "/socket");
            var first = connectQueue(client, uri, joined); var second = connectQueue(client, uri, idle);
            var third = connectQueue(client, URI.create("ws://127.0.0.1:" + port + "/api/workspaces/" + otherWorkspace + "/socket"), other);
            sockets.addAll(java.util.List.of(first, second, third)); join(first, joined, document); join(third, other, otherDocument);
            UUID selected = workspace;
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
                membership.remove(selected, owner, memberId);
                assertFalse(joined.closed.isDone()); assertFalse(idle.closed.isDone()); status.setRollbackOnly();
            });
            first.sendText("{\"type\":\"TEST_MESSAGE\",\"version\":1,\"message\":\"after rollback\"}", true).join();
            assertNotNull(joined.messages.poll(5, TimeUnit.SECONDS));
            membership.setRole(workspace, owner, new ChangeWorkspaceMemberRequest(member, WorkspaceRole.VIEWER));
            assertEquals(1008, joined.closed.get(5, TimeUnit.SECONDS)); assertEquals(1008, idle.closed.get(5, TimeUnit.SECONDS));
            third.sendText("{\"type\":\"TEST_MESSAGE\",\"version\":1,\"message\":\"other workspace remains\"}", true).join();
            assertNotNull(other.messages.poll(5, TimeUnit.SECONDS)); assertFalse(other.closed.isDone());
            var viewer = new QueueListener(); var readSocket = connectQueue(client, uri, viewer); sockets.add(readSocket); join(readSocket, viewer, document);
            readSocket.sendText("{\"type\":\"SUBMIT_UPDATE\",\"version\":1,\"epoch\":0,\"updateId\":\"" + UUID.randomUUID() + "\",\"payload\":\"AAA=\"}", true).join();
            assertTrue(viewer.messages.poll(5, TimeUnit.SECONDS).contains("ACCESS_DENIED"));
            membership.remove(workspace, owner, memberId); assertEquals(1008, viewer.closed.get(5, TimeUnit.SECONDS));
        } finally {
            for (var socket : sockets) socket.abort();
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", workspace); jdbc.update("DELETE FROM workspaces WHERE id=?", workspace); }
            if (otherWorkspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", otherWorkspace); jdbc.update("DELETE FROM workspaces WHERE id=?", otherWorkspace); }
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?)", ownerId, memberId);
        }
    }

    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry) {
        String url = TestDatabaseGuard.verifiedUrl();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("WORKSPACE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("WORKSPACE_TEST_DB_PASSWORD"));
        registry.add("workspace.dev.password", () -> "test-only-password");
        registry.add("workspace.websocket.allowed-origin", () -> ORIGIN);
        registry.add("workspace.delivery.enabled", () -> "false");
    }

    @TestConfiguration static class TestEndpoints {
        @Bean ExpireSessionEndpoint expireSessionEndpoint() { return new ExpireSessionEndpoint(); }
    }
    @RestController static class ExpireSessionEndpoint {
        @GetMapping("/test/socket-expire") void shortTimeout(HttpServletRequest request) { request.getSession(false).setMaxInactiveInterval(1); }
    }

    @Test void handshakeProtocolLogoutAndExpiry() throws Exception {
        UUID ownerId = UUID.randomUUID(), outsiderId = UUID.randomUUID();
        String owner = "socket-owner-" + ownerId + "@example.com", outsider = "socket-out-" + outsiderId + "@example.com";
        String base = "http://127.0.0.1:" + port;
        UUID workspace = null;
        try {
            users.create(ownerId, owner, encoder.encode("test-user-password"));
            users.create(outsiderId, outsider, encoder.encode("test-user-password"));
            workspace = workspaces.create(owner, "socket test").id();
            HttpClient alice = client(), stranger = client(), anonymous = client();
            login(alice, base, owner); login(stranger, base, outsider);
            URI uri = URI.create("ws://127.0.0.1:" + port + "/api/workspaces/" + workspace + "/socket");
            assertRejected(anonymous, uri, ORIGIN, 401);
            HttpClient bootstrap = client(); login(bootstrap, base, "learner");
            assertRejected(bootstrap, uri, ORIGIN, 403);
            assertRejected(stranger, uri, ORIGIN, 404);
            assertRejected(alice, uri, "http://evil.example", 403);
            assertRejected(alice, uri, base, 403); // Same host still requires the configured origin.
            assertRejected(alice, uri, null, 403);
            assertRejected(alice, URI.create("ws://127.0.0.1:" + port + "/api/workspaces/" + UUID.randomUUID() + "/socket"), ORIGIN, 404);
            var listener = new Listener();
            WebSocket socket = connect(alice, uri, ORIGIN, listener);
            socket.sendText("{\"type\":\"HELLO\",\"version\":1}", true).join();
            String welcome = listener.message.get(5, TimeUnit.SECONDS);
            assertTrue(welcome.contains("\"type\":\"WELCOME\""));
            assertTrue(welcome.contains(workspace.toString()));
            assertTrue(welcome.contains("\"userId\":\"" + ownerId + "\""));
            assertEquals(1, registry.count());
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            listener.closed.get(5, TimeUnit.SECONDS);
            awaitEmpty();

            var unsupported = new Listener();
            WebSocket unsupportedSocket = connect(alice, uri, ORIGIN, unsupported);
            unsupportedSocket.sendText("{\"type\":\"HELLO\",\"version\":2}", true).join();
            assertEquals(1008, unsupported.closed.get(5, TimeUnit.SECONDS));
            awaitEmpty();

            var binary = new Listener();
            WebSocket binarySocket = connect(alice, uri, ORIGIN, binary);
            binarySocket.sendBinary(java.nio.ByteBuffer.wrap(new byte[] { 1 }), true).join();
            assertEquals(1003, binary.closed.get(5, TimeUnit.SECONDS));
            awaitEmpty();

            var malformed = new Listener();
            WebSocket invalid = connect(alice, uri, ORIGIN, malformed);
            invalid.sendText("{\"type\":\"HELLO\",\"version\":1.0}", true).join();
            assertEquals(1008, malformed.closed.get(5, TimeUnit.SECONDS));
            awaitEmpty();

            var logoutListener = new Listener();
            WebSocket logoutSocket = connect(alice, uri, ORIGIN, logoutListener);
            logoutSocket.sendText("{\"type\":\"HELLO\",\"version\":1}", true).join();
            logoutListener.message.get(5, TimeUnit.SECONDS);
            HttpClient otherSession = client(); login(otherSession, base, owner);
            var otherListener = new Listener();
            WebSocket otherSocket = connect(otherSession, uri, ORIGIN, otherListener);
            otherSocket.sendText("{\"type\":\"HELLO\",\"version\":1}", true).join();
            otherListener.message.get(5, TimeUnit.SECONDS);
            String csrf = token(get(alice, base + "/api/csrf").body());
            var logout = HttpRequest.newBuilder(URI.create(base + "/logout")).header("X-CSRF-TOKEN", csrf).POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(302, alice.send(logout, HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(1008, logoutListener.closed.get(5, TimeUnit.SECONDS));
            assertFalse(otherListener.closed.isDone());
            assertEquals(1, registry.count());
            otherSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            otherListener.closed.get(5, TimeUnit.SECONDS);
            awaitEmpty();
            assertRejected(alice, uri, ORIGIN, 401);

            HttpClient expiring = client(); login(expiring, base, owner);
            var expiryListener = new Listener();
            WebSocket expirySocket = connect(expiring, uri, ORIGIN, expiryListener);
            expirySocket.sendText("{\"type\":\"HELLO\",\"version\":1}", true).join();
            expiryListener.message.get(5, TimeUnit.SECONDS);
            assertEquals(200, get(expiring, base + "/test/socket-expire").statusCode());
            Thread.sleep(2200);
            assertEquals(401, get(expiring, base + "/api/me").statusCode());
            assertEquals(1008, expiryListener.closed.get(5, TimeUnit.SECONDS));
            awaitEmpty();
        } finally {
            if (workspace != null) jdbc.update("DELETE FROM workspaces WHERE id = ?", workspace);
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?)", ownerId, outsiderId);
        }
    }

    private void awaitEmpty() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (registry.count() != 0 && System.nanoTime() < end) Thread.sleep(10);
        assertEquals(0, registry.count());
    }

    @Test void savedFollowsCommitDuplicatesAreSafeAndFailedOutboxNeverAcknowledges() throws Exception {
        UUID ownerId = UUID.randomUUID(), viewerId = UUID.randomUUID();
        String owner = "saved-owner-" + ownerId + "@example.com", viewer = "saved-viewer-" + viewerId + "@example.com";
        String base = "http://127.0.0.1:" + port;
        UUID workspace = null;
        var sockets = new java.util.ArrayList<WebSocket>();
        try {
            users.create(ownerId, owner, encoder.encode("test-user-password")); users.create(viewerId, viewer, encoder.encode("test-user-password"));
            workspace = workspaces.create(owner, "saved checkpoint").id();
            membership.setRole(workspace, owner, new ChangeWorkspaceMemberRequest(viewer, WorkspaceRole.VIEWER));
            UUID doc = documents.create(workspace, owner, "changes").id();
            String payload = "AQEBAAQBB2NvbnRlbnQKSGVsbG8gSmF2YQA=";
            var checkpoint = java.util.Base64.getDecoder().decode(payload);
            org.mockito.Mockito.doReturn(new YjsEngineClient.Candidate("Hello Java", checkpoint)).when(engine).reconstruct(org.mockito.ArgumentMatchers.anyList());
            HttpClient ownerClient = client(), viewerClient = client(); login(ownerClient, base, owner); login(viewerClient, base, viewer);
            URI uri = URI.create("ws://127.0.0.1:" + port + "/api/workspaces/" + workspace + "/socket");
            var ownerListener = new QueueListener(); var viewerListener = new QueueListener();
            WebSocket ownerSocket = connectQueue(ownerClient, uri, ownerListener), viewerSocket = connectQueue(viewerClient, uri, viewerListener);
            sockets.add(ownerSocket); sockets.add(viewerSocket); join(ownerSocket, ownerListener, doc); join(viewerSocket, viewerListener, doc);
            UUID updateId = UUID.randomUUID();
            String request = "{\"type\":\"SUBMIT_UPDATE\",\"version\":1,\"epoch\":0,\"updateId\":\"" + updateId + "\",\"payload\":\"" + payload + "\"}";
            var insertedButUncommitted = new java.util.concurrent.CountDownLatch(1);
            var permitCommit = new java.util.concurrent.CountDownLatch(1);
            org.mockito.Mockito.doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                insertedButUncommitted.countDown();
                if (!permitCommit.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting to commit");
                return result;
            }).when(updateRepository).insertOutbox(org.mockito.ArgumentMatchers.eq(doc), org.mockito.ArgumentMatchers.eq(0L), org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any(java.util.UUID.class));
            try {
                ownerSocket.sendText(request, true).join();
                assertTrue(insertedButUncommitted.await(5, TimeUnit.SECONDS));
                assertNull(ownerListener.messages.poll(250, TimeUnit.MILLISECONDS));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Integer.class, doc));
            } finally { permitCommit.countDown(); }
            String saved = ownerListener.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(saved); assertTrue(saved.contains("\"type\":\"SAVED\"")); assertTrue(saved.contains("\"sequence\":1"));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Integer.class, doc));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_update_outbox WHERE document_id=?", Integer.class, doc));
            assertEquals(1L, jdbc.queryForObject("SELECT head_sequence FROM document_streams WHERE document_id=?", Long.class, doc));
            assertArrayEquals(checkpoint, jdbc.queryForObject("SELECT checkpoint FROM document_streams WHERE document_id=?", byte[].class, doc));
            assertNull(viewerListener.messages.poll(300, TimeUnit.MILLISECONDS)); // No pre-commit or post-commit broadcasting in Lesson 21.
            org.mockito.Mockito.doThrow(new YjsEngineException(false)).when(engine).reconstruct(org.mockito.ArgumentMatchers.anyList());
            ownerSocket.sendText(request, true).join();
            assertTrue(ownerListener.messages.poll(5, TimeUnit.SECONDS).contains("\"duplicate\":true"));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Integer.class, doc));
            ownerSocket.sendText(request.replace(updateId.toString(), UUID.randomUUID().toString()), true).join();
            assertTrue(ownerListener.messages.poll(5, TimeUnit.SECONDS).contains("ENGINE_UNAVAILABLE"));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Integer.class, doc));
            org.mockito.Mockito.doThrow(new YjsEngineException(true, true)).when(engine).reconstruct(org.mockito.ArgumentMatchers.anyList());
            ownerSocket.sendText(request.replace(updateId.toString(), UUID.randomUUID().toString()), true).join();
            assertTrue(ownerListener.messages.poll(5, TimeUnit.SECONDS).contains("DOCUMENT_LIMIT"));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Integer.class, doc));
            org.mockito.Mockito.doReturn(new YjsEngineClient.Candidate("Hello Java", checkpoint)).when(engine).reconstruct(org.mockito.ArgumentMatchers.anyList());
            String conflict = request.replace(payload, "AQELAAQBB2NvbnRlbnQDYWJjAA==");
            ownerSocket.sendText(conflict, true).join();
            assertTrue(ownerListener.messages.poll(5, TimeUnit.SECONDS).contains("UPDATE_ID_CONFLICT"));
            viewerSocket.sendText(request.replace(updateId.toString(), UUID.randomUUID().toString()), true).join();
            assertTrue(viewerListener.messages.poll(5, TimeUnit.SECONDS).contains("ACCESS_DENIED"));
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("simulated outbox failure"))
                    .when(updateRepository).insertOutbox(org.mockito.ArgumentMatchers.eq(doc), org.mockito.ArgumentMatchers.eq(0L), org.mockito.ArgumentMatchers.eq(2L), org.mockito.ArgumentMatchers.any(java.util.UUID.class));
            ownerSocket.sendText(request.replace(updateId.toString(), UUID.randomUUID().toString()), true).join();
            assertEquals(1011, ownerListener.closed.get(5, TimeUnit.SECONDS));
            assertNull(ownerListener.messages.poll(300, TimeUnit.MILLISECONDS));
            assertNull(viewerListener.messages.poll(300, TimeUnit.MILLISECONDS));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Integer.class, doc));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_update_outbox WHERE document_id=?", Integer.class, doc));
            assertEquals(1L, jdbc.queryForObject("SELECT head_sequence FROM document_streams WHERE document_id=?", Long.class, doc));
        } finally {
            for (WebSocket socket : sockets) socket.abort();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (rooms.count() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id = ?", workspace); jdbc.update("DELETE FROM workspaces WHERE id = ?", workspace); }
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?)", ownerId, viewerId);
        }
    }

    @Test void concurrentRetriesCreateOneUpdateAndRevocationDuringValidationPreventsCommit() throws Exception {
        UUID ownerId = UUID.randomUUID(), editorId = UUID.randomUUID(), workspace = null;
        String owner = "updates-owner-" + ownerId + "@example.com", editor = "updates-editor-" + editorId + "@example.com";
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            users.create(ownerId, owner, encoder.encode("test-user-password")); users.create(editorId, editor, encoder.encode("test-user-password"));
            workspace = workspaces.create(owner, "update concurrency").id();
            membership.setRole(workspace, owner, new ChangeWorkspaceMemberRequest(editor, WorkspaceRole.EDITOR));
            UUID document = documents.create(workspace, owner, "document").id();
            UUID targetWorkspace = workspace;
            byte[] payload = java.util.Base64.getDecoder().decode("AQEBAAQBB2NvbnRlbnQKSGVsbG8gSmF2YQA=");
            var reached = new java.util.concurrent.CountDownLatch(2);
            org.mockito.Mockito.doAnswer(invocation -> {
                reached.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for competing validation");
                return new YjsEngineClient.Candidate("Hello Java", payload);
            }).when(engine).reconstruct(org.mockito.ArgumentMatchers.anyList());
            UUID update = UUID.randomUUID();
            var first = CompletableFuture.supplyAsync(() -> updateService.submit(targetWorkspace, document, ownerId, 0, update, payload, () -> true));
            var second = CompletableFuture.supplyAsync(() -> updateService.submit(targetWorkspace, document, ownerId, 0, update, payload, () -> true));
            assertTrue(reached.await(5, TimeUnit.SECONDS)); release.countDown();
            var one = first.get(5, TimeUnit.SECONDS); var two = second.get(5, TimeUnit.SECONDS);
            assertEquals(1, one.sequence()); assertEquals(1, two.sequence());
            assertNotEquals(one.duplicate(), two.duplicate());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Integer.class, document));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_update_outbox WHERE document_id=?", Integer.class, document));

            var bothCandidates = new java.util.concurrent.CountDownLatch(2);
            var releaseCandidates = new java.util.concurrent.CountDownLatch(1);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            org.mockito.Mockito.doAnswer(invocation -> {
                if (calls.incrementAndGet() <= 2) {
                    bothCandidates.countDown();
                    if (!releaseCandidates.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for candidates");
                }
                return new YjsEngineClient.Candidate("Hello Java", payload);
            }).when(engine).reconstruct(org.mockito.ArgumentMatchers.anyList());
            var distinctA = CompletableFuture.supplyAsync(() -> updateService.submit(targetWorkspace, document, ownerId, 0, UUID.randomUUID(), payload, () -> true));
            var distinctB = CompletableFuture.supplyAsync(() -> updateService.submit(targetWorkspace, document, ownerId, 0, UUID.randomUUID(), payload, () -> true));
            try { assertTrue(bothCandidates.await(5, TimeUnit.SECONDS)); }
            finally { releaseCandidates.countDown(); }
            assertEquals(java.util.Set.of(2L, 3L), java.util.Set.of(distinctA.get(5, TimeUnit.SECONDS).sequence(), distinctB.get(5, TimeUnit.SECONDS).sequence()));
            assertTrue(calls.get() >= 3, "The losing candidate must be revalidated on the new head");

            var validating = new java.util.concurrent.CountDownLatch(1);
            var allowValidation = new java.util.concurrent.CountDownLatch(1);
            org.mockito.Mockito.doAnswer(invocation -> {
                validating.countDown();
                if (!allowValidation.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for revocation");
                return new YjsEngineClient.Candidate("Hello Java", payload);
            }).when(engine).reconstruct(org.mockito.ArgumentMatchers.anyList());
            var editorWrite = CompletableFuture.supplyAsync(() -> updateService.submit(targetWorkspace, document, editorId, 0, UUID.randomUUID(), payload, () -> true));
            try {
                assertTrue(validating.await(5, TimeUnit.SECONDS));
                membership.remove(targetWorkspace, owner, editorId);
            } finally { allowValidation.countDown(); }
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> editorWrite.get(5, TimeUnit.SECONDS));
            assertInstanceOf(DocumentUpdateException.class, failure.getCause());
            assertEquals("ACCESS_DENIED", ((DocumentUpdateException) failure.getCause()).code());
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Integer.class, document));
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM document_update_outbox WHERE document_id=?", Integer.class, document));
        } finally {
            release.countDown();
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id = ?", workspace); jdbc.update("DELETE FROM workspaces WHERE id = ?", workspace); }
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?)", ownerId, editorId);
        }
    }

    @Test void logoutWaitsForAnAlreadyStartedCommitAndClosesTheSocket() throws Exception {
        UUID ownerId = UUID.randomUUID(), workspace = null;
        String owner = "logout-race-" + ownerId + "@example.com", base = "http://127.0.0.1:" + port;
        WebSocket socket = null;
        var allowCommit = new java.util.concurrent.CountDownLatch(1);
        try {
            users.create(ownerId, owner, encoder.encode("test-user-password"));
            workspace = workspaces.create(owner, "logout race").id();
            UUID document = documents.create(workspace, owner, "document").id();
            String payload = "AQEBAAQBB2NvbnRlbnQKSGVsbG8gSmF2YQA=";
            org.mockito.Mockito.doReturn(new YjsEngineClient.Candidate("Hello Java", java.util.Base64.getDecoder().decode(payload)))
                    .when(engine).reconstruct(org.mockito.ArgumentMatchers.anyList());
            var insideCommit = new java.util.concurrent.CountDownLatch(1);
            org.mockito.Mockito.doAnswer(invocation -> {
                Object result = invocation.callRealMethod(); insideCommit.countDown();
                if (!allowCommit.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for commit");
                return result;
            }).when(updateRepository).insertOutbox(org.mockito.ArgumentMatchers.eq(document), org.mockito.ArgumentMatchers.eq(0L), org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any(java.util.UUID.class));
            HttpClient client = client(); login(client, base, owner);
            String csrf = token(get(client, base + "/api/csrf").body());
            URI uri = URI.create("ws://127.0.0.1:" + port + "/api/workspaces/" + workspace + "/socket");
            var listener = new QueueListener(); socket = connectQueue(client, uri, listener); join(socket, listener, document);
            socket.sendText("{\"type\":\"SUBMIT_UPDATE\",\"version\":1,\"epoch\":0,\"updateId\":\"" + UUID.randomUUID() + "\",\"payload\":\"" + payload + "\"}", true).join();
            assertTrue(insideCommit.await(5, TimeUnit.SECONDS));
            var logout = HttpRequest.newBuilder(URI.create(base + "/logout")).header("X-CSRF-TOKEN", csrf).POST(HttpRequest.BodyPublishers.noBody()).build();
            var response = client.sendAsync(logout, HttpResponse.BodyHandlers.discarding());
            try { assertThrows(java.util.concurrent.TimeoutException.class, () -> response.get(250, TimeUnit.MILLISECONDS)); }
            finally { allowCommit.countDown(); }
            assertEquals(302, response.get(5, TimeUnit.SECONDS).statusCode());
            assertEquals(1008, listener.closed.get(5, TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Integer.class, document));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_update_outbox WHERE document_id=?", Integer.class, document));
        } finally {
            allowCommit.countDown();
            if (socket != null) socket.abort();
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id = ?", workspace); jdbc.update("DELETE FROM workspaces WHERE id = ?", workspace); }
            jdbc.update("DELETE FROM app_users WHERE id = ?", ownerId);
        }
    }

    @Test void roomsDeliverOnlyToAuthorizedSameDocumentAndRecheckRevocation() throws Exception {
        UUID ownerId = UUID.randomUUID(), viewerId = UUID.randomUUID(), otherId = UUID.randomUUID();
        String owner = "room-owner-" + ownerId + "@example.com", viewer = "room-viewer-" + viewerId + "@example.com", other = "room-other-" + otherId + "@example.com";
        String base = "http://127.0.0.1:" + port;
        UUID workspace = null, otherWorkspace = null;
        var sockets = new java.util.ArrayList<WebSocket>();
        try {
            users.create(ownerId, owner, encoder.encode("test-user-password")); users.create(viewerId, viewer, encoder.encode("test-user-password"));
            users.create(otherId, other, encoder.encode("test-user-password"));
            workspace = workspaces.create(owner, "room workspace").id(); otherWorkspace = workspaces.create(other, "other workspace").id();
            membership.setRole(workspace, owner, new ChangeWorkspaceMemberRequest(viewer, WorkspaceRole.VIEWER));
            UUID doc = documents.create(workspace, owner, "room").id(), anotherDoc = documents.create(workspace, owner, "other document").id();
            UUID gatedDoc = documents.create(workspace, owner, "gated rich document", null, "RICH_TEXT_V1").id();
            UUID otherDoc = documents.create(otherWorkspace, other, "private").id();
            HttpClient first = client(), second = client(), third = client(), fourth = client();
            login(first, base, owner); login(second, base, viewer); login(third, base, owner); login(fourth, base, other);
            URI uri = URI.create("ws://127.0.0.1:" + port + "/api/workspaces/" + workspace + "/socket");
            URI otherUri = URI.create("ws://127.0.0.1:" + port + "/api/workspaces/" + otherWorkspace + "/socket");
            var a = new QueueListener(); var b = new QueueListener(); var c = new QueueListener(); var d = new QueueListener();
            WebSocket sa = connectQueue(first, uri, a), sb = connectQueue(second, uri, b), sc = connectQueue(third, uri, c), sd = connectQueue(fourth, otherUri, d);
            sockets.addAll(java.util.List.of(sa, sb, sc, sd));
            var incompatible = new QueueListener(); WebSocket wrongFormat = connectQueue(first, uri, incompatible); sockets.add(wrongFormat);
            wrongFormat.sendText("{\"type\":\"JOIN\",\"version\":1,\"documentId\":\"" + doc + "\",\"format\":\"RICH_TEXT_V1\"}", true).join();
            String rejection = incompatible.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(rejection); assertTrue(rejection.contains("\"code\":\"SCHEMA_UNSUPPORTED\""));
            wrongFormat.sendText("{\"type\":\"TEST_MESSAGE\",\"version\":1,\"message\":\"not joined\"}", true).join();
            assertEquals(1008, incompatible.closed.get(5, TimeUnit.SECONDS));
            var gated = new QueueListener(); WebSocket gatedSocket = connectQueue(first, uri, gated); sockets.add(gatedSocket);
            gatedSocket.sendText("{\"type\":\"JOIN\",\"version\":1,\"documentId\":\"" + gatedDoc + "\",\"format\":\"RICH_TEXT_V1\"}", true).join();
            String gatedResponse = gated.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(gatedResponse); assertTrue(gatedResponse.contains("SCHEMA_UNSUPPORTED"));
            var explicit = new QueueListener(); WebSocket supported = connectQueue(first, uri, explicit); sockets.add(supported);
            supported.sendText("{\"type\":\"JOIN\",\"version\":1,\"documentId\":\"" + doc + "\",\"format\":\"PLAIN_TEXT_V1\"}", true).join();
            String joined = explicit.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(joined); assertTrue(joined.contains("\"format\":\"PLAIN_TEXT_V1\""));
            supported.sendText("{\"type\":\"LEAVE\",\"version\":1}", true).join();
            assertTrue(explicit.messages.poll(5, TimeUnit.SECONDS).contains("\"type\":\"LEFT\""));
            join(sa, a, doc); join(sb, b, doc); join(sc, c, anotherDoc); join(sd, d, otherDoc);
            sa.sendText("{\"type\":\"TEST_MESSAGE\",\"version\":1,\"message\":\"hello\"}", true).join();
            String own = a.messages.poll(5, TimeUnit.SECONDS), received = b.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(own); assertNotNull(received); assertEquals(own, received);
            assertTrue(received.contains(ownerId.toString())); assertTrue(received.contains(doc.toString()));
            assertNull(c.messages.poll(300, TimeUnit.MILLISECONDS)); assertNull(d.messages.poll(300, TimeUnit.MILLISECONDS));
            membership.remove(workspace, owner, viewerId);
            sa.sendText("{\"type\":\"TEST_MESSAGE\",\"version\":1,\"message\":\"after removal\"}", true).join();
            assertNotNull(a.messages.poll(5, TimeUnit.SECONDS));
            assertEquals(1008, b.closed.get(5, TimeUnit.SECONDS));
            assertNull(b.messages.poll(300, TimeUnit.MILLISECONDS));
            sc.sendText("{\"type\":\"LEAVE\",\"version\":1}", true).join();
            assertTrue(c.messages.poll(5, TimeUnit.SECONDS).contains("\"type\":\"LEFT\""));
            var spoof = new QueueListener(); WebSocket forged = connectQueue(first, uri, spoof); sockets.add(forged);
            forged.sendText("{\"type\":\"JOIN\",\"version\":1,\"documentId\":\"" + otherDoc + "\"}", true).join();
            assertEquals(1008, spoof.closed.get(5, TimeUnit.SECONDS));
            var impersonation = new QueueListener(); WebSocket attacker = connectQueue(first, uri, impersonation); sockets.add(attacker);
            join(attacker, impersonation, doc);
            attacker.sendText("{\"type\":\"TEST_MESSAGE\",\"version\":1,\"message\":\"hi\",\"userId\":\"" + viewerId + "\"}", true).join();
            assertEquals(1008, impersonation.closed.get(5, TimeUnit.SECONDS));
        } finally {
            for (WebSocket socket : sockets) socket.abort();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (rooms.count() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
            try { assertEquals(0, rooms.count()); }
            finally {
                if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id = ?", workspace); jdbc.update("DELETE FROM workspaces WHERE id = ?", workspace); }
                if (otherWorkspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id = ?", otherWorkspace); jdbc.update("DELETE FROM workspaces WHERE id = ?", otherWorkspace); }
                jdbc.update("DELETE FROM app_users WHERE id IN (?, ?, ?)", ownerId, viewerId, otherId);
            }
        }
    }
    @Test void presenceUsesAuthenticatedIdentityAndStaysInsideItsDocument() throws Exception {
        UUID ownerId = UUID.randomUUID(), viewerId = UUID.randomUUID(), workspace = null;
        String owner = "presence-owner-" + ownerId + "@example.com", viewer = "presence-viewer-" + viewerId + "@example.com";
        String base = "http://127.0.0.1:" + port;
        var sockets = new java.util.ArrayList<WebSocket>();
        try {
            users.create(ownerId, owner, encoder.encode("test-user-password"));
            users.create(viewerId, viewer, encoder.encode("test-user-password"));
            workspace = workspaces.create(owner, "presence workspace").id();
            membership.setRole(workspace, owner, new ChangeWorkspaceMemberRequest(viewer, WorkspaceRole.VIEWER));
            UUID doc = documents.create(workspace, owner, "presence").id(), other = documents.create(workspace, owner, "other").id();
            HttpClient first = client(), second = client(), third = client();
            login(first, base, owner); login(second, base, viewer); login(third, base, owner);
            URI uri = URI.create("ws://127.0.0.1:" + port + "/api/workspaces/" + workspace + "/socket");
            var a = new QueueListener(); var b = new QueueListener(); var c = new QueueListener(); var legacy = new QueueListener();
            WebSocket sa = connectQueue(first, uri, a), sb = connectQueue(second, uri, b), sc = connectQueue(third, uri, c);
            WebSocket old = connectQueue(third, uri, legacy);
            sockets.addAll(java.util.List.of(sa, sb, sc, old));
            joinPresence(sa, a, doc); joinPresence(sb, b, doc); joinPresence(sc, c, other);
            join(old, legacy, doc);
            sa.sendText("{\"type\":\"PRESENCE\",\"version\":1,\"anchor\":\"AA==\",\"head\":\"AQ==\"}", true).join();
            String presence = b.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(presence); assertTrue(presence.contains("\"userId\":\"" + ownerId + "\""));
            var value = tools.jackson.databind.json.JsonMapper.builder().build().readTree(presence);
            assertEquals(owner, value.path("email").asText());
            String presenceId = value.path("presenceId").asText();
            assertNotNull(UUID.fromString(presenceId));
            assertNull(c.messages.poll(300, TimeUnit.MILLISECONDS));
            assertNull(legacy.messages.poll(300, TimeUnit.MILLISECONDS));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Integer.class, doc));
            sa.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            String left = b.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(left); assertTrue(left.contains("\"type\":\"PRESENCE_LEFT\"")); assertTrue(left.contains(presenceId));
            assertNull(legacy.messages.poll(300, TimeUnit.MILLISECONDS));
            sb.sendText("{\"type\":\"PRESENCE\",\"version\":1,\"anchor\":\"AA==\",\"head\":\"AQ==\",\"userId\":\"" + ownerId + "\"}", true).join();
            assertEquals(1008, b.closed.get(5, TimeUnit.SECONDS));
        } finally {
            for (WebSocket socket : sockets) socket.abort();
            awaitEmpty();
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id = ?", workspace); jdbc.update("DELETE FROM workspaces WHERE id = ?", workspace); }
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?)", ownerId, viewerId);
        }
    }

    private static class QueueListener implements WebSocket.Listener {
        final java.util.concurrent.BlockingQueue<String> messages = new java.util.concurrent.LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) { messages.add(data.toString()); socket.request(1); return null; }
        @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { closed.complete(code); return null; }
        @Override public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(error); }
    }
    private WebSocket connectQueue(HttpClient client, URI uri, QueueListener listener) throws Exception {
        WebSocket socket = client.newWebSocketBuilder().header("Origin", ORIGIN).buildAsync(uri, listener).join();
        socket.sendText("{\"type\":\"HELLO\",\"version\":1}", true).join();
        assertTrue(listener.messages.poll(5, TimeUnit.SECONDS).contains("\"type\":\"WELCOME\""));
        return socket;
    }
    private void join(WebSocket socket, QueueListener listener, UUID document) throws Exception {
        socket.sendText("{\"type\":\"JOIN\",\"version\":1,\"documentId\":\"" + document + "\"}", true).join();
        assertTrue(listener.messages.poll(5, TimeUnit.SECONDS).contains("\"type\":\"JOINED\""));
    }
    private void joinPresence(WebSocket socket, QueueListener listener, UUID document) throws Exception {
        socket.sendText("{\"type\":\"JOIN\",\"version\":1,\"documentId\":\"" + document + "\",\"format\":\"PLAIN_TEXT_V1\",\"presence\":true}", true).join();
        assertTrue(listener.messages.poll(5, TimeUnit.SECONDS).contains("\"type\":\"JOINED\""));
    }
    private static class Listener implements WebSocket.Listener {
        final CompletableFuture<String> message = new CompletableFuture<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) { message.complete(data.toString()); socket.request(1); return null; }
        @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { closed.complete(code); return null; }
        @Override public void onError(WebSocket socket, Throwable error) { message.completeExceptionally(error); closed.completeExceptionally(error); }
    }
    private WebSocket connect(HttpClient client, URI uri, String origin, Listener listener) {
        var builder = client.newWebSocketBuilder(); if (origin != null) builder.header("Origin", origin);
        return builder.buildAsync(uri, listener).join();
    }
    private void assertRejected(HttpClient client, URI uri, String origin, int status) {
        CompletionException exception = assertThrows(CompletionException.class, () -> connect(client, uri, origin, new Listener()));
        assertInstanceOf(WebSocketHandshakeException.class, exception.getCause());
        assertEquals(status, ((WebSocketHandshakeException) exception.getCause()).getResponse().statusCode());
    }
    private HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(); }
    private HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private String token(String body) {
        var match = java.util.regex.Pattern.compile("\"token\":\"([^\"]+)\"").matcher(body);
        if (!match.find()) throw new IllegalStateException("No CSRF token");
        return match.group(1);
    }
    private void login(HttpClient client, String base, String email) throws Exception {
        String csrf = token(get(client, base + "/api/csrf").body());
        String password = email.equals("learner") ? "test-only-password" : "test-user-password";
        String body = "username=" + URLEncoder.encode(email, StandardCharsets.UTF_8) + "&password=" + password;
        var request = HttpRequest.newBuilder(URI.create(base + "/login")).header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-CSRF-TOKEN", csrf).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(302, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(200, get(client, base + "/api/me").statusCode());
    }
}
