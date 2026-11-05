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
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/** Real Java/Node transport check; this is not a full collaboration-provider proof. */
@EnabledIfEnvironmentVariable(named = "WORKSPACE_RUN_YJS_ENGINE_TESTS", matches = "true")
class YjsEngineBoundaryTest {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void preservesBinaryUpdatesAndRecoversCheckpointAcrossProcessRestart() throws Exception {
        Path engine = Path.of("..", "experiments", "yjs-private-engine").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(engine.resolve("dist/src/server.js")), "Run npm ci and npm run build in the private engine first");
        var fixture = new Properties();
        try (var reader = Files.newBufferedReader(engine.resolve("fixtures/dependency-order.properties"))) { fixture.load(reader); }
        String first = roundTrip(fixture.getProperty("first")), dependent = roundTrip(fixture.getProperty("dependent"));
        String deletion = roundTrip(fixture.getProperty("deletion"));
        int port; try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Process process = start(engine, port);
        String checkpoint;
        try {
            awaitReady(process, port);
            var missing = request(port, dependent);
            assertEquals(422, missing.statusCode());
            assertEquals("MISSING_DEPENDENCY", json.readTree(missing.body()).path("code").asText());
            var client = new YjsEngineClient("http://127.0.0.1:" + port, json, Duration.ofSeconds(5));
            var bootstrap = new RichTextContract(json).initialUpdate();
            assertTrue(client.reconstructRich(java.util.List.of(bootstrap)).initialized());
            var richFixture = new Properties();
            try (var reader = Files.newBufferedReader(engine.resolve("fixtures/rich-text.properties"))) { richFixture.load(reader); }
            byte[] richUpdate = Base64.getDecoder().decode(roundTrip(richFixture.getProperty("update")));
            assertFalse(client.reconstructRich(java.util.List.of(new byte[] {0, 0})).initialized());
            var richCandidate = client.reconstructRich(java.util.List.of(richUpdate));
            assertTrue(richCandidate.initialized());
            byte[] richCopy = richCandidate.checkpoint(); richCopy[0] ^= 1;
            assertNotEquals(richCopy[0], richCandidate.checkpoint()[0]);
            assertTrue(client.reconstructRich(java.util.List.of(richCandidate.checkpoint())).initialized());
            assertTrue(assertThrows(YjsEngineException.class, () -> client.reconstruct(java.util.List.of(richUpdate))).rejected());
            assertTrue(assertThrows(YjsEngineException.class, () -> client.reconstructRich(java.util.List.of(Base64.getDecoder().decode(first)))).rejected());
            YjsEngineException rejected = assertThrows(YjsEngineException.class,
                    () -> client.reconstruct(java.util.List.of(Base64.getDecoder().decode(dependent))));
            assertTrue(rejected.rejected());
            var candidate = client.reconstruct(java.util.List.of(Base64.getDecoder().decode(first), Base64.getDecoder().decode(dependent), Base64.getDecoder().decode(deletion)));
            assertEquals(fixture.getProperty("expected"), candidate.text());
            byte[] copy = candidate.checkpoint(); copy[0] ^= 1;
            assertNotEquals(copy[0], candidate.checkpoint()[0]);
            var response = request(port, deletion, dependent, first, dependent);
            assertEquals(200, response.statusCode());
            var result = json.readTree(response.body());
            assertEquals("candidate", result.path("status").asText());
            assertEquals(fixture.getProperty("expected"), result.path("text").asText());
            checkpoint = roundTrip(result.path("checkpoint").asText());
        } finally { stop(process); }
        process = start(engine, port);
        try {
            awaitReady(process, port);
            var restored = request(port, checkpoint);
            assertEquals(200, restored.statusCode());
            assertEquals(fixture.getProperty("expected"), json.readTree(restored.body()).path("text").asText());
        } finally { stop(process); }
    }

    private String roundTrip(String encoded) {
        assertNotNull(encoded);
        return Base64.getEncoder().encodeToString(Base64.getDecoder().decode(encoded));
    }
    private Process start(Path engine, int port) throws Exception {
        var builder = new ProcessBuilder("node", "dist/src/server.js").directory(engine.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("YJS_ENGINE_PORT", Integer.toString(port));
        return builder.start();
    }
    private void stop(Process process) throws Exception {
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); }
    }
    private void awaitReady(Process process, int port) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < until) {
            if (!process.isAlive()) fail("Yjs engine exited before it was ready");
            try {
                var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                        .timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 404) return;
            } catch (java.io.IOException ignored) { }
            Thread.sleep(50);
        }
        fail("Yjs engine did not start within the deadline");
    }
    private HttpResponse<String> request(int port, String... updates) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("version", 1, "updates", java.util.List.of(updates)));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/reconstruct"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
