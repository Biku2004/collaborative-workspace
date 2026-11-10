package com.example.collaborativeworkspace.collaboration.transport;

import com.example.collaborativeworkspace.auth.UserRepository;
import com.example.collaborativeworkspace.collaboration.engine.YjsEngineClient;
import com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateService;
import com.example.collaborativeworkspace.documents.DocumentService;
import com.example.collaborativeworkspace.support.TestDatabaseGuard;
import com.example.collaborativeworkspace.workspaces.WorkspaceService;

import static org.junit.jupiter.api.Assertions.*;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "WORKSPACE_RUN_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentDeliveryRepairTest {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired WorkspaceService workspaces;
    @Autowired DocumentService documents;
    @Autowired DocumentUpdateService updateService;
    @Autowired DocumentDeliveryService delivery;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    @MockitoSpyBean YjsEngineClient engine;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        String database = TestDatabaseGuard.verifiedUrl();
        registry.add("spring.datasource.url", () -> database);
        registry.add("spring.datasource.username", () -> System.getenv("WORKSPACE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("WORKSPACE_TEST_DB_PASSWORD"));
        registry.add("workspace.dev.password", () -> "test-only-password");
        registry.add("workspace.websocket.allowed-origin", () -> "http://127.0.0.1:5173");
        registry.add("workspace.delivery.relay-enabled", () -> "false");
        registry.add("workspace.delivery.poll-ms", () -> "100");
    }

    @Test void missedDeliveryIsRepairedWhileConnectedAndPendingOutboxCanBeReplayed() throws Exception {
        UUID ownerId = UUID.randomUUID(), workspace = null, secondWorkspace = null;
        String email = "repair-" + ownerId + "@example.com", base = "http://127.0.0.1:" + port;
        WebSocket socket = null, differentDocumentSocket = null, differentWorkspaceSocket = null;
        var diagnostics = new com.example.collaborativeworkspace.support.DiagnosticLogCapture(com.example.collaborativeworkspace.observability.WorkspaceTelemetry.class);
        try {
            users.create(ownerId, email, encoder.encode("test-user-password"));
            workspace = workspaces.create(email, "repair").id();
            UUID document = documents.create(workspace, email, "document").id();
            UUID differentDocument = documents.create(workspace, email, "different document").id();
            secondWorkspace = workspaces.create(email, "different workspace").id();
            UUID differentWorkspaceDocument = documents.create(secondWorkspace, email, "different workspace document").id();
            byte[] update = java.util.Base64.getDecoder().decode("AQEBAAQBB2NvbnRlbnQKSGVsbG8gSmF2YQA=");
            org.mockito.Mockito.doReturn(new YjsEngineClient.Candidate("Hello Java", update)).when(engine).reconstruct(org.mockito.ArgumentMatchers.anyList());
            HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            String csrf = get(client, base + "/api/csrf");
            var matcher = java.util.regex.Pattern.compile("\"token\":\"([^\"]+)\"").matcher(csrf); assertTrue(matcher.find());
            String form = "username=" + URLEncoder.encode(email, StandardCharsets.UTF_8) + "&password=test-user-password";
            var login = HttpRequest.newBuilder(URI.create(base + "/login")).header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-CSRF-TOKEN", matcher.group(1)).POST(HttpRequest.BodyPublishers.ofString(form)).build();
            assertEquals(302, client.send(login, HttpResponse.BodyHandlers.discarding()).statusCode());
            var listener = new Listener(json);
            socket = client.newWebSocketBuilder().header("Origin", "http://127.0.0.1:5173")
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/workspaces/" + workspace + "/socket"), listener).join();
            socket.sendText("{\"type\":\"HELLO\",\"version\":1}", true).join(); listener.next("WELCOME");
            socket.sendText("{\"type\":\"JOIN\",\"version\":1,\"documentId\":\"" + document + "\"}", true).join(); listener.next("JOINED");
            var otherDocumentListener = new Listener(json);
            differentDocumentSocket = join(client, workspace, differentDocument, otherDocumentListener);
            var otherWorkspaceListener = new Listener(json);
            differentWorkspaceSocket = join(client, secondWorkspace, differentWorkspaceDocument, otherWorkspaceListener);
            // Relay is disabled: the connection remains open while nine committed notifications are missed.
            for (int i = 0; i < 9; i++) updateService.submit(workspace, document, ownerId, 0, UUID.randomUUID(), update, () -> true);
            JsonNode head = listener.headAtLeast(9);
            assertEquals(9, head.path("sequence").longValue());
            assertEquals(9, jdbc.queryForObject("SELECT count(*) FROM document_update_outbox WHERE document_id=? AND published_at IS NULL", Integer.class, document));
            socket.sendText("{\"type\":\"SYNC\",\"version\":1,\"epoch\":0,\"after\":0}", true).join();
            JsonNode page = listener.next("SYNC_PAGE");
            assertEquals(8, page.path("updates").size()); assertTrue(page.path("hasMore").asBoolean());
            assertEquals(8, page.path("after").longValue());
            for (int i = 0; i < 8; i++) assertEquals(i + 1, page.path("updates").get(i).path("sequence").longValue());
            socket.sendText("{\"type\":\"SYNC\",\"version\":1,\"epoch\":0,\"after\":8}", true).join();
            JsonNode last = listener.next("SYNC_PAGE");
            assertEquals(1, last.path("updates").size()); assertFalse(last.path("hasMore").asBoolean());
            assertEquals(9, last.path("updates").get(0).path("sequence").longValue());
            socket.sendText("{\"type\":\"SYNC\",\"version\":1,\"epoch\":0,\"after\":10}", true).join();
            assertEquals("INVALID_CURSOR", listener.next("REJECTED").path("code").asText());
            socket.sendText("{\"type\":\"SYNC\",\"version\":1,\"epoch\":1,\"after\":0}", true).join();
            assertEquals("EPOCH_CHANGED", listener.next("REJECTED").path("code").asText());
            // Resuming the relay later may duplicate data already repaired by replay.
            assertTrue(delivery.relayPending() >= 9);
            for (int i = 1; i <= 9; i++) assertEquals(i, listener.next("UPDATE").path("sequence").longValue());
            UUID correlation = jdbc.queryForObject("SELECT correlation_id FROM document_update_outbox WHERE document_id=? AND sequence=1", UUID.class, document);
            long diagnosticDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!diagnostics.contains(correlation, "socket_send") && System.nanoTime() < diagnosticDeadline) Thread.sleep(10);
            for (String stage : java.util.List.of("accept", "validate", "commit", "relay", "socket_send")) assertTrue(diagnostics.contains(correlation, stage), stage);
            assertTrue(metrics.get("workspace.operation").tags("stage", "socket_send", "outcome", "success").timer().count() >= 9);
            assertEquals(3, metrics.get("workspace.connections").gauge().value());
            assertTrue(diagnostics.events.stream().allMatch(event -> event.getThrowableProxy() == null && !com.example.collaborativeworkspace.support.DiagnosticLogCapture.rendered(event).contains(email) && !com.example.collaborativeworkspace.support.DiagnosticLogCapture.rendered(event).contains("Hello Java") && !com.example.collaborativeworkspace.support.DiagnosticLogCapture.rendered(event).contains("AQEBAAQB")));
            otherDocumentListener.assertNoType("UPDATE");
            otherWorkspaceListener.assertNoType("UPDATE");
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document_update_outbox WHERE document_id=? AND published_at IS NULL", Integer.class, document));
            jdbc.update("UPDATE document_update_outbox SET published_at=NULL WHERE document_id=? AND sequence=1", document);
            assertTrue(delivery.relayPending() >= 1);
            assertEquals(1, listener.next("UPDATE").path("sequence").longValue());
            // Large updates are split by the byte limit even when fewer than eight records remain.
            UUID largeDocument = documents.create(workspace, email, "large page").id();
            byte[] large = new byte[8192]; java.util.Arrays.fill(large, (byte) 1);
            updateService.submit(workspace, largeDocument, ownerId, 0, UUID.randomUUID(), large, () -> true);
            updateService.submit(workspace, largeDocument, ownerId, 0, UUID.randomUUID(), large, () -> true);
            JsonNode largePage = json.readTree(delivery.sync(workspace, largeDocument, ownerId, 0, 0));
            assertEquals(1, largePage.path("updates").size()); assertTrue(largePage.path("hasMore").asBoolean());
            assertTrue(delivery.sync(workspace, largeDocument, ownerId, 0, 0).getBytes(StandardCharsets.UTF_8).length <= 14000);
            assertEquals(1, json.readTree(delivery.sync(workspace, largeDocument, ownerId, 0, 1)).path("updates").size());
            // Detect corrupted or missing log entries instead of presenting a gap as a contiguous page.
            jdbc.update("DELETE FROM document_updates WHERE document_id=? AND sequence=2", document);
            UUID targetWorkspace = workspace;
            assertThrows(IllegalStateException.class, () -> delivery.sync(targetWorkspace, document, ownerId, 0, 0));
        } finally {
            diagnostics.close();
            if (socket != null) socket.abort();
            if (differentDocumentSocket != null) differentDocumentSocket.abort();
            if (differentWorkspaceSocket != null) differentWorkspaceSocket.abort();
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", workspace); jdbc.update("DELETE FROM workspaces WHERE id=?", workspace); }
            if (secondWorkspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", secondWorkspace); jdbc.update("DELETE FROM workspaces WHERE id=?", secondWorkspace); }
            jdbc.update("DELETE FROM app_users WHERE id=?", ownerId);
        }
    }
    private String get(HttpClient client, String url) throws Exception { return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString()).body(); }
    private WebSocket join(HttpClient client, UUID workspace, UUID document, Listener listener) throws Exception {
        WebSocket socket = client.newWebSocketBuilder().header("Origin", "http://127.0.0.1:5173")
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/workspaces/" + workspace + "/socket"), listener).join();
        socket.sendText("{\"type\":\"HELLO\",\"version\":1}", true).join(); listener.next("WELCOME");
        socket.sendText("{\"type\":\"JOIN\",\"version\":1,\"documentId\":\"" + document + "\"}", true).join(); listener.next("JOINED");
        return socket;
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "WORKSPACE_RUN_YJS_ENGINE_TESTS", matches = "true")
    void committedOutboxSurvivesAnAbruptJvmCrashBeforeRelay() throws Exception {
        UUID actor = UUID.randomUUID();
        Process node = null, child = null;
        try {
            int enginePort; try (var socket = new java.net.ServerSocket(0)) { enginePort = socket.getLocalPort(); }
            var nodeBuilder = new ProcessBuilder("node", "dist/src/server.js")
                    .directory(java.nio.file.Path.of("..", "experiments", "yjs-private-engine").toAbsolutePath().normalize().toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            nodeBuilder.environment().put("YJS_ENGINE_PORT", Integer.toString(enginePort));
            node = nodeBuilder.start();
            HttpClient probe = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(1)).build();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8); boolean ready = false;
            while (System.nanoTime() < deadline && node.isAlive()) {
                try {
                    if (probe.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + enginePort + "/")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 404) { ready = true; break; }
                } catch (java.io.IOException ignored) { }
                Thread.sleep(50);
            }
            assertTrue(ready, "Engine did not start; build the private engine first");
            String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java";
            var builder = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-cp", System.getProperty("java.class.path"), DeliveryCrashFixture.class.getName(), actor.toString());
            builder.redirectErrorStream(true);
            var env = builder.environment();
            env.put("WORKSPACE_DB_URL", System.getenv("WORKSPACE_TEST_DB_URL")); env.put("WORKSPACE_DB_USER", System.getenv("WORKSPACE_TEST_DB_USER"));
            env.put("WORKSPACE_DB_PASSWORD", System.getenv("WORKSPACE_TEST_DB_PASSWORD")); env.put("WORKSPACE_DEV_PASSWORD", "test-only-password");
            env.put("SPRING_PROFILES_ACTIVE", "dev"); env.put("SERVER_PORT", "0"); env.put("WORKSPACE_DELIVERY_ENABLED", "false");
            env.put("WORKSPACE_YJS_ENGINE_URL", "http://127.0.0.1:" + enginePort);
            child = builder.start();
            Process running = child;
            var committed = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (var reader = running.inputReader()) {
                    String line;
                    while ((line = reader.readLine()) != null) if (line.startsWith("COMMITTED:")) return line;
                    throw new IllegalStateException("Child exited before committing the fixture");
                } catch (java.io.IOException ex) { throw new IllegalStateException("Could not read child output", ex); }
            });
            String[] marker = committed.get(30, TimeUnit.SECONDS).split(":");
            UUID workspace = UUID.fromString(marker[1]), document = UUID.fromString(marker[2]);
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_update_outbox WHERE document_id=? AND published_at IS NULL", Integer.class, document));
            UUID correlation = jdbc.queryForObject("SELECT correlation_id FROM document_update_outbox WHERE document_id=?", UUID.class, document);
            assertNotNull(correlation);
            child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            assertEquals(correlation, jdbc.queryForObject("SELECT correlation_id FROM document_update_outbox WHERE document_id=?", UUID.class, document));
            // A surviving/restarted process reads the committed log and can resume its outbox relay.
            JsonNode replay = json.readTree(delivery.sync(workspace, document, actor, 0, 0));
            assertEquals(1, replay.path("updates").size());
            assertEquals(1, replay.path("updates").get(0).path("sequence").longValue());
            assertTrue(delivery.relayPending() >= 1);
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document_update_outbox WHERE document_id=? AND published_at IS NULL", Integer.class, document));
        } finally {
            if (child != null && child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
            if (node != null && node.isAlive()) { node.destroyForcibly(); node.waitFor(5, TimeUnit.SECONDS); }
            jdbc.update("DELETE FROM documents WHERE workspace_id IN (SELECT id FROM workspaces WHERE created_by=?)", actor);
            jdbc.update("DELETE FROM workspaces WHERE created_by=?", actor);
            jdbc.update("DELETE FROM app_users WHERE id=?", actor);
        }
    }
    private static final class Listener implements WebSocket.Listener {
        private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        private final JsonMapper json;
        Listener(JsonMapper json) { this.json = json; }
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) { messages.add(json.readTree(data.toString())); socket.request(1); return null; }
        JsonNode next(String type) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < end) {
                JsonNode value = messages.poll(1, TimeUnit.SECONDS);
                if (value != null && type.equals(value.path("type").asText())) return value;
            }
            throw new AssertionError("Timed out waiting for " + type);
        }
        JsonNode headAtLeast(long minimum) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < end) {
                JsonNode value = messages.poll(1, TimeUnit.SECONDS);
                if (value != null && "HEAD".equals(value.path("type").asText()) && value.path("sequence").longValue() >= minimum) return value;
            }
            throw new AssertionError("Timed out waiting for repair head");
        }
        void assertNoType(String type) throws Exception {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (System.nanoTime() < end) {
                JsonNode value = messages.poll(50, TimeUnit.MILLISECONDS);
                if (value != null) assertNotEquals(type, value.path("type").asText());
            }
        }
    }
}
