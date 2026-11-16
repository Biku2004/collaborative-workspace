package com.example.collaborativeworkspace.collaboration.transport;

import com.example.collaborativeworkspace.auth.UserRepository;
import com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateService;
import com.example.collaborativeworkspace.documents.DocumentService;
import com.example.collaborativeworkspace.support.TestDatabaseGuard;
import com.example.collaborativeworkspace.workspaces.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Deliberately paced functional workload, not a production throughput benchmark. */
@EnabledIfEnvironmentVariable(named = "WORKSPACE_RUN_LOAD_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class HotRoomLoadIntegrationTest {
    private static Process engine;
    private static final String ORIGIN = "http://127.0.0.1:5173";
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired WorkspaceService workspaces;
    @Autowired WorkspaceMembershipService membership;
    @Autowired DocumentService documents;
    @Autowired DocumentUpdateService updates;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired MeterRegistry meters;

    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry) throws Exception {
        String database = TestDatabaseGuard.verifiedUrl();
        int enginePort; try (var socket = new ServerSocket(0)) { enginePort = socket.getLocalPort(); }
        Path directory = Path.of("..", "experiments", "yjs-private-engine").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(directory.resolve("dist/src/server.js")), "Build the private Yjs engine first");
        var builder = new ProcessBuilder("node", "dist/src/server.js").directory(directory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("YJS_ENGINE_PORT", "" + enginePort); engine = builder.start();
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).build();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8); boolean ready = false;
        while (engine.isAlive() && System.nanoTime() < until) {
            try {
                if (client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + enginePort + "/")).timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 404) { ready = true; break; }
            } catch (java.io.IOException ignored) { }
            Thread.sleep(50);
        }
        if (!ready) { engine.destroyForcibly(); throw new IllegalStateException("Private Yjs engine did not start"); }
        registry.add("spring.datasource.url", () -> database);
        registry.add("spring.datasource.username", () -> System.getenv("WORKSPACE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("WORKSPACE_TEST_DB_PASSWORD"));
        registry.add("workspace.dev.password", () -> "load-bootstrap-password");
        registry.add("workspace.yjs-engine-url", () -> "http://127.0.0.1:" + enginePort);
        registry.add("workspace.websocket.allowed-origin", () -> ORIGIN);
        registry.add("workspace.delivery.poll-ms", () -> "100");
        registry.add("workspace.delivery.enabled", () -> "true");
        registry.add("workspace.delivery.relay-enabled", () -> "true");
        registry.add("workspace.search.indexing.enabled", () -> "false");
        registry.add("workspace.cluster.enabled", () -> "false");
        registry.add("workspace.observability.projections.enabled", () -> "false");
    }

    @AfterAll static void stopEngine() throws Exception {
        if (engine != null && engine.isAlive()) { engine.destroyForcibly(); engine.waitFor(5, TimeUnit.SECONDS); }
    }

    @Test void sixteenHotRoomPeersReceiveContiguousUpdatesWhileAColdRoomStillSynchronizes() throws Exception {
        UUID owner = UUID.randomUUID(), editor = UUID.randomUUID(), viewer = UUID.randomUUID(), workspace = null;
        String ownerEmail = "load-owner-" + owner + "@example.com", editorEmail = "load-editor-" + editor + "@example.com", viewerEmail = "load-viewer-" + viewer + "@example.com";
        var sockets = new ArrayList<WebSocket>(); var hot = new ArrayList<Listener>();
        var sampler = Executors.newSingleThreadScheduledExecutor();
        var peakMessages = new AtomicInteger(); var peakBytes = new AtomicInteger(); var peakTasks = new AtomicInteger();
        double overflowBefore = counter("queue_overflow"), rejectionBefore = counter("executor_rejected");
        try {
            String password = encoder.encode("load-test-password");
            users.create(owner, ownerEmail, password); users.create(editor, editorEmail, password); users.create(viewer, viewerEmail, password);
            workspace = workspaces.create(ownerEmail, "load fixture").id();
            membership.setRole(workspace, ownerEmail, new ChangeWorkspaceMemberRequest(editorEmail, WorkspaceRole.EDITOR));
            membership.setRole(workspace, ownerEmail, new ChangeWorkspaceMemberRequest(viewerEmail, WorkspaceRole.VIEWER));
            UUID document = documents.create(workspace, ownerEmail, "hot fixture").id(), coldDocument = documents.create(workspace, ownerEmail, "cold fixture").id();
            for (String email : List.of(ownerEmail, editorEmail)) {
                var client = login(email);
                for (int i = 0; i < 8; i++) { var listener = new Listener(json); hot.add(listener); sockets.add(join(client, workspace, document, listener)); }
            }
            var cold = new Listener(json); var coldSocket = join(login(viewerEmail), workspace, coldDocument, cold); sockets.add(coldSocket);
            assertEquals(17, meters.get("workspace.connections").gauge().value());
            sampler.scheduleAtFixedRate(() -> {
                peakMessages.accumulateAndGet((int) meters.get("workspace.socket.queued.messages").gauge().value(), Math::max);
                peakBytes.accumulateAndGet((int) meters.get("workspace.socket.queued.bytes").gauge().value(), Math::max);
                peakTasks.accumulateAndGet((int) meters.get("workspace.sender.tasks").gauge().value(), Math::max);
            }, 0, 2, TimeUnit.MILLISECONDS);
            var acceptance = new ArrayList<Long>(); var fanout = new ArrayList<Long>(); var coldProbe = new ArrayList<Long>();
            // The same valid CRDT bytes under distinct request IDs create 12 committed sequences.
            // This measures acceptance/fanout work without pretending to grow document content.
            byte[] payload = Base64.getDecoder().decode("AQEBAAQBB2NvbnRlbnQKSGVsbG8gSmF2YQA=");
            for (long sequence = 1; sequence <= 12; sequence++) {
                long start = System.nanoTime();
                var saved = updates.submit(workspace, document, owner, 0, UUID.randomUUID(), payload, () -> true);
                acceptance.add(System.nanoTime() - start); assertEquals(sequence, saved.sequence());
                // Probe the other room while this round's hot-room fanout is still outstanding.
                long probe = System.nanoTime();
                coldSocket.sendText("{\"type\":\"SYNC\",\"version\":1,\"epoch\":0,\"after\":0}", true).get(5, TimeUnit.SECONDS);
                long received = start;
                for (var listener : hot) {
                    var frame = listener.next("UPDATE"); assertEquals(sequence, frame.value().path("sequence").longValue());
                    received = Math.max(received, frame.at());
                }
                fanout.add(received - start);
                var page = cold.next("SYNC_PAGE"); assertEquals(0, page.value().path("updates").size());
                coldProbe.add(page.at() - probe);
            }
            assertEquals(12, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Integer.class, document));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Integer.class, coldDocument));
            assertEquals(overflowBefore, counter("queue_overflow")); assertEquals(rejectionBefore, counter("executor_rejected"));
            for (var listener : hot) { assertEquals(0, listener.closed.get()); assertNull(listener.failure); }
            assertEquals(0, cold.closed.get()); assertNull(cold.failure);
            assertTrue(peakMessages.get() <= 17 * BoundedSocketSender.MAX_MESSAGES);
            assertTrue(peakBytes.get() <= 17 * BoundedSocketSender.MAX_BYTES, "Sampled aggregate includes both rooms");
            assertTrue(peakTasks.get() <= 128);
            System.out.println("HOT_ROOM_MEASUREMENT " + json.writeValueAsString(Map.of(
                    "hotPeers", 16, "coldPeers", 1, "commits", 12, "acceptanceMs", summary(acceptance),
                    "allHotPeersMs", summary(fanout), "coldSyncMs", summary(coldProbe),
                    "sampledPeakQueuedMessages", peakMessages.get(), "sampledPeakQueuedBytes", peakBytes.get(), "sampledPeakSenderTasks", peakTasks.get())));
        } finally {
            sampler.shutdownNow(); sampler.awaitTermination(5, TimeUnit.SECONDS);
            for (var socket : sockets) socket.abort();
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", workspace); jdbc.update("DELETE FROM workspaces WHERE id=?", workspace); }
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?, ?)", owner, editor, viewer);
        }
    }

    private Map<String, Double> summary(List<Long> samples) {
        var sorted = samples.stream().sorted().toList();
        return Map.of("p50", sorted.get((int) Math.ceil(sorted.size() * .50) - 1) / 1_000_000.0,
                "p95", sorted.get((int) Math.ceil(sorted.size() * .95) - 1) / 1_000_000.0,
                "max", sorted.getLast() / 1_000_000.0);
    }
    private double counter(String signal) { var meter = meters.find("workspace.transport").tag("signal", signal).counter(); return meter == null ? 0 : meter.count(); }
    private HttpClient login(String email) throws Exception {
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String base = "http://127.0.0.1:" + port;
        var csrf = json.readTree(client.send(HttpRequest.newBuilder(URI.create(base + "/api/csrf")).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
        String form = "username=" + URLEncoder.encode(email, StandardCharsets.UTF_8) + "&password=load-test-password";
        var response = client.send(HttpRequest.newBuilder(URI.create(base + "/login")).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded").header(csrf.path("headerName").asText(), csrf.path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(302, response.statusCode()); return client;
    }
    private WebSocket join(HttpClient client, UUID workspace, UUID document, Listener listener) throws Exception {
        var socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).header("Origin", ORIGIN)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/workspaces/" + workspace + "/socket"), listener).get(5, TimeUnit.SECONDS);
        socket.sendText("{\"type\":\"HELLO\",\"version\":1}", true).get(5, TimeUnit.SECONDS); listener.next("WELCOME");
        socket.sendText("{\"type\":\"JOIN\",\"version\":1,\"documentId\":\"" + document + "\"}", true).get(5, TimeUnit.SECONDS); listener.next("JOINED");
        return socket;
    }
    private record Frame(JsonNode value, long at) {}
    private static final class Listener implements WebSocket.Listener {
        private final BlockingQueue<Frame> frames = new ArrayBlockingQueue<>(64);
        private final StringBuilder fragments = new StringBuilder();
        private final JsonMapper json;
        final AtomicInteger closed = new AtomicInteger();
        volatile Throwable failure;
        Listener(JsonMapper json) { this.json = json; }
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                JsonNode value = json.readTree(fragments.toString()); fragments.setLength(0);
                if (!"HEAD".equals(value.path("type").asText()) && !frames.offer(new Frame(value, System.nanoTime()))) failure = new IllegalStateException("Test receiver queue full");
            }
            socket.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { closed.incrementAndGet(); return null; }
        @Override public void onError(WebSocket socket, Throwable error) { failure = error; }
        Frame next(String type) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < until) {
                if (failure != null) throw new AssertionError("Test receiver failed", failure);
                Frame frame = frames.poll(100, TimeUnit.MILLISECONDS);
                if (frame != null) { assertEquals(type, frame.value().path("type").asText()); return frame; }
            }
            throw new AssertionError("Timed out waiting for " + type);
        }
    }
}
