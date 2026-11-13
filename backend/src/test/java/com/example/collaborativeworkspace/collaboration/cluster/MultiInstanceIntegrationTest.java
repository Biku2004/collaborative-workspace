package com.example.collaborativeworkspace.collaboration.cluster;

import com.example.collaborativeworkspace.CollaborativeWorkspaceApplication;
import com.example.collaborativeworkspace.auth.UserRepository;
import com.example.collaborativeworkspace.documents.DocumentService;
import com.example.collaborativeworkspace.support.TestDatabaseGuard;
import com.example.collaborativeworkspace.workspaces.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL, two independent servlet applications, Redis and two browser origins. */
@EnabledIfEnvironmentVariable(named = "WORKSPACE_RUN_CLUSTER_TESTS", matches = "true")
class MultiInstanceIntegrationTest {
    private final List<Process> processes = new ArrayList<>();
    private final Path frontend = Path.of("..", "frontend").toAbsolutePath().normalize();
    private final Path logs = Path.of("target", "lesson35");
    private static int freePort() throws Exception { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static void await(BooleanSupplier condition, String failure) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < until) { if (condition.getAsBoolean()) return; Thread.sleep(100); }
        fail(failure);
    }
    private Process start(ProcessBuilder builder) throws Exception { Process process = builder.start(); processes.add(process); return process; }
    private void ready(int port, int status) throws Exception {
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofMillis(500)).build();
        await(() -> { try { return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == status; } catch (Exception ignored) { return false; } }, "Local test server did not become ready");
    }
    private ConfigurableApplicationContext node(String name, int origin, int engine, int redis, String namespace, String database) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("spring.datasource.url", database);
        settings.put("spring.datasource.username", System.getenv("WORKSPACE_TEST_DB_USER"));
        settings.put("spring.datasource.password", System.getenv("WORKSPACE_TEST_DB_PASSWORD"));
        settings.put("spring.datasource.hikari.maximum-pool-size", 4);
        settings.put("workspace.dev.password", "cluster-bootstrap-password");
        settings.put("workspace.cluster.enabled", true);
        settings.put("workspace.cluster.namespace", namespace);
        settings.put("workspace.cluster.key", Objects.requireNonNull(System.getenv("WORKSPACE_CLUSTER_KEY")));
        settings.put("workspace.cluster.redis.password", Objects.requireNonNull(System.getenv("WORKSPACE_CLUSTER_REDIS_PASSWORD")));
        settings.put("workspace.cluster.redis.port", redis);
        settings.put("workspace.yjs-engine-url", "http://127.0.0.1:" + engine);
        settings.put("workspace.websocket.allowed-origin", "http://127.0.0.1:" + origin);
        settings.put("workspace.delivery.poll-ms", 300);
        // Force A to own relay work so B's UPDATE proves Redis delivery, not a local relay.
        settings.put("workspace.delivery.relay-enabled", "NODE_A".equals(name));
        settings.put("workspace.observability.projections.enabled", false);
        settings.put("server.port", 0);
        settings.put("server.servlet.session.cookie.name", name + "_SESSION");
        var app = new SpringApplication(CollaborativeWorkspaceApplication.class);
        app.setAdditionalProfiles("dev");
        app.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("cluster-test", settings)));
        return app.run();
    }
    private void vite(int origin, int backend, String name) throws Exception {
        var builder = new ProcessBuilder("node", "node_modules/vite/bin/vite.js", "--port", "" + origin, "--strictPort")
                .directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(logs.resolve(name + ".log").toFile());
        builder.environment().put("WORKSPACE_PROXY_TARGET", "http://127.0.0.1:" + backend);
        start(builder); ready(origin, 200);
    }
    @Test void browsersConvergeAcrossNodesAndRepairMissedNotifications() throws Exception {
        String database = TestDatabaseGuard.verifiedUrl();
        Files.createDirectories(logs);
        UUID aliceId = UUID.randomUUID(), bobId = UUID.randomUUID();
        String alice = "cluster-alice-" + aliceId + "@example.com", bob = "cluster-bob-" + bobId + "@example.com";
        UUID workspace = null;
        ConfigurableApplicationContext a = null, b = null;
        HttpServer control = null;
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(database, System.getenv("WORKSPACE_TEST_DB_USER"), System.getenv("WORKSPACE_TEST_DB_PASSWORD")));
        try (var proxy = new RedisTestProxy()) {
            int originA = freePort(), originB = freePort(), enginePort = freePort();
            var engine = new ProcessBuilder("node", "dist/src/server.js").directory(Path.of("..", "experiments", "yjs-private-engine").toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            engine.environment().put("YJS_ENGINE_PORT", "" + enginePort); start(engine); ready(enginePort, 404);
            String namespace = "test_" + UUID.randomUUID();
            // Both start while Redis is unreachable; the servlet application must remain usable.
            a = node("NODE_A", originA, enginePort, proxy.port(), namespace, database);
            b = node("NODE_B", originB, enginePort, proxy.port(), namespace, database);
            var listenerA = a.getBean(RedisMessageListenerContainer.class);
            var listenerB = b.getBean(RedisMessageListenerContainer.class);
            var bridgeA = a.getBean(ClusterBridge.class); var bridgeB = b.getBean(ClusterBridge.class);
            proxy.restore(); await(() -> listenerA.isListening() && listenerB.isListening(), "Redis subscriptions did not recover after unavailable startup");
            var users = a.getBean(UserRepository.class); var encoder = a.getBean(PasswordEncoder.class);
            users.create(aliceId, alice, encoder.encode("browser-test-password")); users.create(bobId, bob, encoder.encode("browser-test-password"));
            String sharedName = "Cluster-" + aliceId;
            workspace = a.getBean(WorkspaceService.class).create(alice, sharedName).id();
            a.getBean(WorkspaceMembershipService.class).setRole(workspace, alice, new ChangeWorkspaceMemberRequest(bob, WorkspaceRole.EDITOR));
            UUID document = a.getBean(DocumentService.class).create(workspace, alice, "Cluster notes").id();
            int portA = ((WebServerApplicationContext) a).getWebServer().getPort(), portB = ((WebServerApplicationContext) b).getWebServer().getPort();
            vite(originA, portA, "vite-a"); vite(originB, portB, "vite-b");
            String token = UUID.randomUUID().toString();
            control = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
            control.createContext("/", exchange -> {
                int status = 204;
                try {
                    if (!"POST".equals(exchange.getRequestMethod()) || !token.equals(exchange.getRequestHeaders().getFirst("X-Test-Key"))) status = 403;
                    else switch (exchange.getRequestURI().getPath()) {
                        case "/drop" -> listenerB.removeMessageListener(bridgeB);
                        case "/resume" -> listenerB.addMessageListener(bridgeB, new ChannelTopic(bridgeB.topic()));
                        case "/block" -> proxy.block();
                        case "/restore" -> proxy.restore();
                        case "/pending" -> assertTrue(jdbc.queryForObject("SELECT count(*) FROM document_update_outbox WHERE document_id=? AND published_at IS NULL", Integer.class, document) > 0);
                        case "/duplicate" -> {
                            UUID event = jdbc.queryForObject("SELECT event_id FROM document_update_outbox WHERE document_id=? ORDER BY sequence DESC LIMIT 1", UUID.class, document);
                            long before = jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Long.class, document);
                            bridgeA.update(event); bridgeA.update(event);
                            assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM document_updates WHERE document_id=?", Long.class, document));
                        }
                        default -> status = 404;
                    }
                } catch (Throwable failure) { status = 500; }
                exchange.sendResponseHeaders(status, -1); exchange.close();
            });
            control.start();
            var runner = new ProcessBuilder("node", "node_modules/@playwright/test/cli.js", "test", "multi-instance.spec.ts", "--reporter=line")
                    .directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(logs.resolve("browser.log").toFile());
            var env = runner.environment();
            env.put("WORKSPACE_E2E_ORIGIN", "http://127.0.0.1:" + originA); env.put("WORKSPACE_E2E_ORIGIN_B", "http://127.0.0.1:" + originB);
            env.put("WORKSPACE_E2E_ALICE", alice); env.put("WORKSPACE_E2E_BOB", bob); env.put("WORKSPACE_E2E_ALICE_ID", aliceId.toString());
            env.put("WORKSPACE_E2E_SHARED_NAME", sharedName); env.put("WORKSPACE_E2E_SHARED_WORKSPACE", workspace.toString());
            env.put("WORKSPACE_E2E_CONTROL", "http://127.0.0.1:" + control.getAddress().getPort()); env.put("WORKSPACE_E2E_CONTROL_KEY", token);
            Path chrome = Path.of("C:/Program Files/Google/Chrome/Application/chrome.exe");
            if (Files.isRegularFile(chrome)) env.putIfAbsent("PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH", chrome.toString());
            Process browser = start(runner);
            assertTrue(browser.waitFor(180, TimeUnit.SECONDS), "Multi-instance browser test timed out");
            assertEquals(0, browser.exitValue(), () -> { try { return Files.readString(logs.resolve("browser.log")); } catch (Exception ex) { return "Browser log unavailable"; } });
            await(() -> listenerA.isListening() && listenerB.isListening(), "Redis did not resubscribe after outage");
            await(() -> jdbc.queryForObject("SELECT count(*) FROM document_update_outbox WHERE document_id=? AND published_at IS NULL", Integer.class, document) == 0, "Outbox did not drain after recovery");
        } finally {
            if (control != null) control.stop(0);
            for (Process process : processes.reversed()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
            }
            if (b != null) b.close(); if (a != null) a.close();
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", workspace); jdbc.update("DELETE FROM workspaces WHERE id=?", workspace); }
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?)", aliceId, bobId);
        }
    }
}
