package com.example.collaborativeworkspace;

import static org.junit.jupiter.api.Assertions.*;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Runs the actual browser through Vite and the Java API, against the isolated test database. */
@EnabledIfEnvironmentVariable(named = "WORKSPACE_RUN_BROWSER_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class BrowserWorkspaceIntegrationTest {
    private static int frontendPort;
    private static Process engine;
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired WorkspaceService workspaces;
    @Autowired WorkspaceMembershipService membership;
    @Autowired DocumentService documents;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry) throws Exception {
        String database = TestDatabaseGuard.verifiedUrl();
        try (var socket = new ServerSocket(0)) { frontendPort = socket.getLocalPort(); }
        registry.add("workspace.websocket.allowed-origin", () -> "http://127.0.0.1:" + frontendPort);
        if ("true".equals(System.getenv("WORKSPACE_RUN_YJS_ENGINE_TESTS"))) {
            int enginePort; try (var socket = new ServerSocket(0)) { enginePort = socket.getLocalPort(); }
            Path directory = Path.of("..", "experiments", "yjs-private-engine").toAbsolutePath().normalize();
            assertTrue(Files.isRegularFile(directory.resolve("dist/src/server.js")), "Build the private Yjs engine first");
            var builder = new ProcessBuilder("node", "dist/src/server.js").directory(directory.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("YJS_ENGINE_PORT", Integer.toString(enginePort));
            engine = builder.start();
            var probe = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).build();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8); boolean ready = false;
            while (engine.isAlive() && System.nanoTime() < until) {
                try {
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + enginePort + "/")).timeout(Duration.ofSeconds(1)).GET().build();
                    if (probe.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 404) { ready = true; break; }
                } catch (java.io.IOException ignored) { }
                Thread.sleep(50);
            }
            if (!ready) { engine.destroyForcibly(); throw new IllegalStateException("Private Yjs engine did not start"); }
            registry.add("workspace.yjs-engine-url", () -> "http://127.0.0.1:" + enginePort);
            registry.add("workspace.delivery.enabled", () -> "true");
            registry.add("workspace.delivery.poll-ms", () -> "100");
            registry.add("workspace.rich.enabled", () -> "true");
        } else registry.add("workspace.delivery.enabled", () -> "false");
        registry.add("spring.datasource.url", () -> database);
        registry.add("spring.datasource.username", () -> System.getenv("WORKSPACE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("WORKSPACE_TEST_DB_PASSWORD"));
        registry.add("workspace.dev.password", () -> "browser-bootstrap-password");
    }

    @AfterAll static void stopEngine() throws Exception {
        if (engine != null && engine.isAlive()) { engine.destroyForcibly(); engine.waitFor(5, TimeUnit.SECONDS); }
    }

    @Test void browserTraversesLoginProxyDatabaseIsolationAndRevocation() throws Exception {
        UUID aliceId = UUID.randomUUID(), bobId = UUID.randomUUID(), aliceWorkspace = null, bobWorkspace = null, sharedWorkspace = null;
        String alice = "browser-alice-" + aliceId + "@example.com", bob = "browser-bob-" + bobId + "@example.com";
        String aliceName = "Alice-" + aliceId, bobName = "Bob-" + bobId;
        Path frontend = Path.of("..", "frontend").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(frontend.resolve("node_modules/vite/bin/vite.js")), "Run npm ci in frontend first");
        Path viteLog = Files.createTempFile("workspace-vite-", ".log");
        Path browserLog = Files.createTempFile("workspace-browser-", ".log");
        Process vite = null, browser = null;
        try {
            users.create(aliceId, alice, encoder.encode("browser-test-password")); users.create(bobId, bob, encoder.encode("browser-test-password"));
            aliceWorkspace = workspaces.create(alice, aliceName).id(); bobWorkspace = workspaces.create(bob, bobName).id();
            String sharedName = "Shared-" + aliceId;
            sharedWorkspace = workspaces.create(alice, sharedName).id();
            membership.setRole(sharedWorkspace, alice, new ChangeWorkspaceMemberRequest(bob, WorkspaceRole.EDITOR));
            documents.create(sharedWorkspace, alice, "Live notes");
            documents.create(sharedWorkspace, alice, "Reconnect notes");
            membership.setRole(bobWorkspace, bob, new ChangeWorkspaceMemberRequest(alice, WorkspaceRole.VIEWER));
            for (int i = 0; i < 20; i++) documents.create(aliceWorkspace, alice, "Document " + i);
            documents.create(aliceWorkspace, alice, "<img src=x onerror=alert(1)>");
            documents.create(bobWorkspace, bob, "Private report");
            var server = new ProcessBuilder("node", "node_modules/vite/bin/vite.js", "--port", Integer.toString(frontendPort), "--strictPort")
                    .directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(viteLog.toFile());
            server.environment().put("WORKSPACE_PROXY_TARGET", "http://127.0.0.1:" + port);
            vite = server.start();
            var probe = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).build();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            boolean ready = false;
            while (vite.isAlive() && System.nanoTime() < deadline) {
                try {
                    if (probe.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + frontendPort + "/")).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200) { ready = true; break; }
                } catch (java.io.IOException ignored) { }
                Thread.sleep(50);
            }
            assertTrue(ready, () -> "Vite failed to start:\n" + safeRead(viteLog));
            var runner = new ProcessBuilder("node", "node_modules/@playwright/test/cli.js", "test", "a-reconnect.spec.ts", "a-rich-integration.spec.ts", "workspace.spec.ts", "editor.spec.ts", "local-document-store.spec.ts", "partial-sync.spec.ts", "rich-surface.spec.ts", "--reporter=line")
                    .directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(browserLog.toFile());
            var env = runner.environment();
            env.put("WORKSPACE_E2E_ORIGIN", "http://127.0.0.1:" + frontendPort);
            env.put("WORKSPACE_E2E_ALICE", alice); env.put("WORKSPACE_E2E_BOB", bob);
            env.put("WORKSPACE_E2E_ALICE_ID", aliceId.toString()); env.put("WORKSPACE_E2E_BOB_WORKSPACE", bobWorkspace.toString());
            env.put("WORKSPACE_E2E_ALICE_NAME", aliceName); env.put("WORKSPACE_E2E_BOB_NAME", bobName);
            env.put("WORKSPACE_E2E_BOB_ID", bobId.toString()); env.put("WORKSPACE_E2E_SHARED_NAME", sharedName);
            env.put("WORKSPACE_E2E_SHARED_WORKSPACE", sharedWorkspace.toString());
            env.put("WORKSPACE_E2E_EDITING", Boolean.toString(engine != null));
            Path chrome = Path.of("C:/Program Files/Google/Chrome/Application/chrome.exe");
            if (Files.isRegularFile(chrome)) env.putIfAbsent("PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH", chrome.toString());
            browser = runner.start();
            assertTrue(browser.waitFor(120, TimeUnit.SECONDS), "Browser verification timed out");
            assertEquals(0, browser.exitValue(), () -> "Browser verification failed:\n" + safeRead(browserLog));
            safeRead(browserLog).lines().filter(line -> line.startsWith("STRUCTURAL_EDIT_MEASUREMENT ")).forEach(System.out::println);
            if (engine != null) {
                // The preceding revocation scenario removes Bob; restore him for account-isolation coverage.
                membership.setRole(sharedWorkspace, alice, new ChangeWorkspaceMemberRequest(bob, WorkspaceRole.EDITOR));
                assertTrue(Files.isRegularFile(frontend.resolve("dist/sw.js")), "Build the frontend before offline browser verification");
                stopProcess(vite);
                var preview = new ProcessBuilder("node", "node_modules/vite/bin/vite.js", "preview", "--port", Integer.toString(frontendPort), "--strictPort")
                        .directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(viteLog.toFile());
                preview.environment().put("WORKSPACE_PROXY_TARGET", "http://127.0.0.1:" + port);
                vite = preview.start();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15); ready = false;
                while (vite.isAlive() && System.nanoTime() < deadline) {
                    try {
                        if (probe.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + frontendPort + "/sw.js")).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200) { ready = true; break; }
                    } catch (java.io.IOException ignored) { }
                    Thread.sleep(50);
                }
                assertTrue(ready, () -> "Production preview failed to start:\n" + safeRead(viteLog));
                runner.command("node", "node_modules/@playwright/test/cli.js", "test", "offline-shell.spec.ts", "--reporter=line");
                browser = runner.start();
                assertTrue(browser.waitFor(60, TimeUnit.SECONDS), "Offline browser verification timed out");
                assertEquals(0, browser.exitValue(), () -> "Offline browser verification failed:\n" + safeRead(browserLog));
            }
        } finally {
            stopProcess(browser);
            stopProcess(vite);
            if (aliceWorkspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", aliceWorkspace); jdbc.update("DELETE FROM workspaces WHERE id=?", aliceWorkspace); }
            if (bobWorkspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", bobWorkspace); jdbc.update("DELETE FROM workspaces WHERE id=?", bobWorkspace); }
            if (sharedWorkspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", sharedWorkspace); jdbc.update("DELETE FROM workspaces WHERE id=?", sharedWorkspace); }
            jdbc.update("DELETE FROM app_users WHERE id IN (?, ?)", aliceId, bobId);
            deleteLog(viteLog); deleteLog(browserLog);
        }
    }
    private static String safeRead(Path file) {
        try { return Files.readString(file); } catch (java.io.IOException ex) { return "Log unavailable"; }
    }
    private static void stopProcess(Process process) throws InterruptedException {
        if (process == null) return;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
    }
    private static void deleteLog(Path path) {
        try { Files.deleteIfExists(path); } catch (java.io.IOException ex) { path.toFile().deleteOnExit(); }
    }
}
