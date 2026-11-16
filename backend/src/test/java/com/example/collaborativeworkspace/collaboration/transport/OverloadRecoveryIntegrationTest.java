package com.example.collaborativeworkspace.collaboration.transport;

import com.example.collaborativeworkspace.auth.UserRepository;
import com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateRepository;
import com.example.collaborativeworkspace.documents.DocumentService;
import com.example.collaborativeworkspace.support.TestDatabaseGuard;
import com.example.collaborativeworkspace.workspaces.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.socket.*;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** A deterministic transport-write stall, followed by real browser reauthorization and durable repair. */
@EnabledIfEnvironmentVariable(named = "WORKSPACE_RUN_OVERLOAD_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class OverloadRecoveryIntegrationTest {
    private static int frontendPort;
    private static Process engine;
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired WorkspaceService workspaces;
    @Autowired WorkspaceMembershipService membership;
    @Autowired DocumentService documents;
    @Autowired DocumentRoomHub rooms;
    @Autowired SocketSessionRegistry sessions;
    @Autowired DocumentUpdateRepository updates;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @MockitoSpyBean WorkspaceSocketHandler handler;
    private static int freePort() throws IOException { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static void ready(int port, int status) throws Exception {
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).build();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < until) {
            try {
                if (client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == status) return;
            } catch (IOException ignored) { }
            Thread.sleep(50);
        }
        fail("Local overload test server did not become ready");
    }
    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry) throws Exception {
        String database = TestDatabaseGuard.verifiedUrl(); frontendPort = freePort(); int enginePort = freePort();
        var builder = new ProcessBuilder("node", "dist/src/server.js").directory(Path.of("..", "experiments", "yjs-private-engine").toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("YJS_ENGINE_PORT", "" + enginePort); engine = builder.start(); ready(enginePort, 404);
        registry.add("spring.datasource.url", () -> database);
        registry.add("spring.datasource.username", () -> System.getenv("WORKSPACE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("WORKSPACE_TEST_DB_PASSWORD"));
        registry.add("workspace.dev.password", () -> "overload-bootstrap-password");
        registry.add("workspace.websocket.allowed-origin", () -> "http://127.0.0.1:" + frontendPort);
        registry.add("workspace.yjs-engine-url", () -> "http://127.0.0.1:" + enginePort);
        registry.add("workspace.delivery.poll-ms", () -> 100);
        registry.add("workspace.cluster.enabled", () -> false);
    }
    @AfterAll static void stopEngine() throws Exception { stop(engine); }
    private static void stop(Process process) throws Exception {
        if (process == null) return; process.descendants().forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
    }
    @Test void overflowingSlowConsumerReconnectsAndResynchronizesWithoutBlockingHealthyEditor() throws Exception {
        UUID aliceId = UUID.randomUUID(), bobId = UUID.randomUUID(), workspace = null;
        String alice = "overload-alice-" + aliceId + "@example.com", bob = "overload-bob-" + bobId + "@example.com";
        var armed = new AtomicBoolean(); var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        var heldPeer = new AtomicReference<DocumentRoomHub.Peer>(); var closedCode = new AtomicInteger(); var configured = new AtomicInteger();
        var decorated = new ConcurrentHashMap<String, WebSocketSession>();
        var peakMessages = new AtomicInteger(); var peakBytes = new AtomicInteger();
        Process vite = null, browser = null; HttpServer control = null;
        Path logs = Path.of("target", "lesson36"); Files.createDirectories(logs);
        try {
            doAnswer(call -> {
                WebSocketSession socket = call.getArgument(0);
                if (socket instanceof WebSocketSessionDecorator) return call.callRealMethod();
                WebSocketSession wrapped = socket;
                if (bobId.toString().equals(socket.getAttributes().get("userId"))) {
                    wrapped = new WebSocketSessionDecorator(socket) {
                        private final AtomicBoolean transportClosed = new AtomicBoolean();
                        @Override public void sendMessage(WebSocketMessage<?> message) throws IOException {
                            if (message instanceof TextMessage text && text.getPayload().contains("\"type\":\"UPDATE\"") && armed.compareAndSet(true, false)) {
                                heldPeer.set(rooms.get(this)); held.countDown();
                                try { if (!release.await(15, TimeUnit.SECONDS)) throw new IOException("Test write stall timed out"); }
                                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("Test write interrupted"); }
                            }
                            if (!transportClosed.get() && isOpen()) super.sendMessage(message);
                        }
                        @Override public void close(CloseStatus status) throws IOException { transportClosed.set(true); closedCode.compareAndSet(0, status.getCode()); release.countDown(); super.close(status); }
                    };
                    decorated.put(socket.getId(), wrapped);
                }
                if (wrapped == socket) call.callRealMethod(); else handler.afterConnectionEstablished(wrapped);
                var nativeSession = ((NativeWebSocketSession) socket).getNativeSession(jakarta.websocket.Session.class);
                assertNotNull(nativeSession);
                var properties = nativeSession.getUserProperties();
                assertEquals(SocketTransportLimits.WRITE_TIMEOUT_MS, properties.get("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT"));
                assertEquals(SocketTransportLimits.CLOSE_SEND_TIMEOUT_MS, properties.get("org.apache.tomcat.websocket.ABNORMAL_SESSION_CLOSE_SEND_TIMEOUT"));
                assertEquals(SocketTransportLimits.CLOSE_HANDSHAKE_TIMEOUT_MS, properties.get("org.apache.tomcat.websocket.SESSION_CLOSE_TIMEOUT"));
                configured.incrementAndGet(); return null;
            }).when(handler).afterConnectionEstablished(any());
            doAnswer(call -> {
                WebSocketSession socket = call.getArgument(0); var wrapped = decorated.remove(socket.getId());
                if (wrapped == null) return call.callRealMethod();
                handler.afterConnectionClosed(wrapped, call.getArgument(1)); return null;
            }).when(handler).afterConnectionClosed(any(), any());
            users.create(aliceId, alice, encoder.encode("browser-test-password")); users.create(bobId, bob, encoder.encode("browser-test-password"));
            String name = "Overload-" + aliceId; workspace = workspaces.create(alice, name).id();
            membership.setRole(workspace, alice, new ChangeWorkspaceMemberRequest(bob, WorkspaceRole.EDITOR));
            UUID document = documents.create(workspace, alice, "Recovery notes").id();
            String token = UUID.randomUUID().toString();
            control = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
            control.createContext("/", exchange -> {
                int status = 204;
                try {
                    if (!"POST".equals(exchange.getRequestMethod()) || !token.equals(exchange.getRequestHeaders().getFirst("X-Test-Key"))) status = 403;
                    else if (exchange.getRequestURI().getPath().equals("/arm")) armed.set(true);
                    else if (exchange.getRequestURI().getPath().equals("/overflow")) {
                        assertTrue(held.await(5, TimeUnit.SECONDS), "Selected browser write did not stall");
                        UUID event = jdbc.queryForObject("SELECT event_id FROM document_update_outbox WHERE document_id=? ORDER BY sequence DESC LIMIT 1", UUID.class, document);
                        var committed = updates.committedEvent(event).orElseThrow(); var update = committed.update();
                        String frame = json.writeValueAsString(Map.of("type", "UPDATE", "version", 1, "documentId", document, "epoch", update.epoch(),
                                "sequence", update.sequence(), "updateId", update.updateId(), "userId", update.actor(), "payload", Base64.getEncoder().encodeToString(update.payload())));
                        var peer = heldPeer.get(); assertNotNull(peer);
                        long before = jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Long.class, document);
                        for (int i = 0; i < 20; i++) {
                            peer.sender.send(frame, () -> peer.allowed(document), committed.correlationId());
                            peakMessages.accumulateAndGet(peer.sender.queuedMessages(), Math::max); peakBytes.accumulateAndGet(peer.sender.queuedBytes(), Math::max);
                        }
                        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Long.class, document));
                        assertEquals(CloseStatus.SESSION_NOT_RELIABLE.getCode(), closedCode.get());
                    } else status = 404;
                } catch (Throwable failure) { status = 500; }
                exchange.sendResponseHeaders(status, -1); exchange.close();
            });
            control.start();
            Path frontend = Path.of("..", "frontend").toAbsolutePath().normalize();
            var server = new ProcessBuilder("node", "node_modules/vite/bin/vite.js", "--port", "" + frontendPort, "--strictPort")
                    .directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(logs.resolve("recovery-vite.log").toFile());
            server.environment().put("WORKSPACE_PROXY_TARGET", "http://127.0.0.1:" + port); vite = server.start(); ready(frontendPort, 200);
            var runner = new ProcessBuilder("node", "node_modules/@playwright/test/cli.js", "test", "overload-recovery.spec.ts", "--reporter=line")
                    .directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(logs.resolve("recovery-browser.log").toFile());
            var env = runner.environment(); env.put("WORKSPACE_E2E_ORIGIN", "http://127.0.0.1:" + frontendPort);
            env.put("WORKSPACE_E2E_ALICE", alice); env.put("WORKSPACE_E2E_BOB", bob); env.put("WORKSPACE_E2E_SHARED_NAME", name);
            env.put("WORKSPACE_E2E_OVERLOAD_CONTROL", "http://127.0.0.1:" + control.getAddress().getPort()); env.put("WORKSPACE_E2E_OVERLOAD_KEY", token);
            Path chrome = Path.of("C:/Program Files/Google/Chrome/Application/chrome.exe"); if (Files.isRegularFile(chrome)) env.putIfAbsent("PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH", chrome.toString());
            browser = runner.start(); assertTrue(browser.waitFor(90, TimeUnit.SECONDS), "Overload browser test timed out");
            assertEquals(0, browser.exitValue(), () -> { try { return Files.readString(logs.resolve("recovery-browser.log")); } catch (Exception ignored) { return "Browser log unavailable"; } });
            assertEquals(BoundedSocketSender.MAX_MESSAGES, peakMessages.get()); assertTrue(peakBytes.get() <= BoundedSocketSender.MAX_BYTES);
            assertEquals(0, heldPeer.get().sender.queuedMessages()); assertEquals(0, heldPeer.get().sender.queuedBytes()); assertTrue(configured.get() >= 3);
            System.out.println("OVERLOAD_RECOVERY_MEASUREMENT peakWaitingMessages=" + peakMessages.get() + " peakWaitingBytes=" + peakBytes.get() + " closeCode=" + closedCode.get() + " configuredNativeSessions=" + configured.get());
        } finally {
            release.countDown(); if (control != null) control.stop(0); stop(browser); stop(vite);
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", workspace); jdbc.update("DELETE FROM workspaces WHERE id=?", workspace); }
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?)", aliceId, bobId);
        }
    }
}
