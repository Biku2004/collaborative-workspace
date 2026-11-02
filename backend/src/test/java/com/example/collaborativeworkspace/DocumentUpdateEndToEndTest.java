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
    @Autowired DocumentService documents;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired YjsEngineClient engineClient;
    @Autowired tools.jackson.databind.json.JsonMapper json;
    static Process engine;

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
        registry.add("spring.datasource.url", () -> database);
        registry.add("spring.datasource.username", () -> System.getenv("WORKSPACE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("WORKSPACE_TEST_DB_PASSWORD"));
        registry.add("workspace.dev.password", () -> "test-only-password");
    }
    @AfterAll static void stopEngine() throws Exception {
        if (engine != null) { engine.destroy(); if (!engine.waitFor(5, TimeUnit.SECONDS)) engine.destroyForcibly(); }
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
