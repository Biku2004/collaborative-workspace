package com.example.collaborativeworkspace;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class YjsEngineClientTest {
    @Test void validatesRichCandidateEnvelopeAndCheckpoint() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var mode = new AtomicInteger();
        var requestBody = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/v2/reconstruct", exchange -> {
            try {
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String body = switch (mode.get()) {
                    case 0 -> "{\"version\":2,\"status\":\"candidate\",\"format\":\"RICH_TEXT_V1\",\"initialized\":true,\"document\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]},\"checkpoint\":\"AAA=\"}";
                    case 1 -> "{\"version\":2,\"status\":\"candidate\",\"format\":\"OTHER\",\"initialized\":true,\"document\":{},\"checkpoint\":\"AAA=\"}";
                    case 2 -> "{\"version\":2,\"status\":\"candidate\",\"format\":\"RICH_TEXT_V1\",\"initialized\":true,\"document\":null,\"checkpoint\":\"AAA=\"}";
                    case 3 -> "{\"version\":2,\"status\":\"candidate\",\"format\":\"RICH_TEXT_V1\",\"initialized\":false,\"document\":{},\"checkpoint\":\"AAA=\"}";
                    case 4 -> "{\"version\":2,\"status\":\"candidate\",\"format\":\"RICH_TEXT_V1\",\"initialized\":true,\"document\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]},\"checkpoint\":\"bad!\"}";
                    case 6 -> "{\"version\":2,\"status\":\"candidate\",\"format\":\"RICH_TEXT_V1\",\"initialized\":false,\"document\":null,\"checkpoint\":\"AAA=\"}";
                    case 7 -> "{\"version\":2,\"status\":\"candidate\",\"format\":\"RICH_TEXT_V1\",\"initialized\":true,\"document\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"text\":\"" + "x".repeat(70000) + "\"}]},\"checkpoint\":\"AAA=\"}";
                    case 8 -> "x".repeat(200000);
                    default -> "{\"version\":2,\"status\":\"candidate\",\"format\":\"RICH_TEXT_V1\",\"initialized\":true,\"document\":{\"type\":\"doc\",\"content\":[]},\"checkpoint\":\"AAA=\"}";
                };
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var json = JsonMapper.builder().build();
            var client = new YjsEngineClient("http://127.0.0.1:" + server.getAddress().getPort(), json, Duration.ofSeconds(2));
            assertTrue(client.reconstructRich(List.of()).initialized());
            var sent = json.readTree(requestBody.get());
            assertEquals(2, sent.path("version").intValue()); assertEquals("RICH_TEXT_V1", sent.path("format").asText());
            assertTrue(sent.path("mode").isMissingNode());
            assertTrue(client.reconstructRichForCommit(List.of(new byte[] {0}, new byte[] {1})).initialized());
            sent = json.readTree(requestBody.get());
            assertEquals("commit", sent.path("mode").asText());
            assertEquals(2, sent.path("updates").size());
            assertTrue(client.reconstructRichForCommit(List.of(new byte[9000], new byte[] {1})).initialized());
            assertThrows(IllegalArgumentException.class, () -> client.reconstructRichForCommit(List.of(new byte[32769], new byte[] {1})));
            assertThrows(IllegalArgumentException.class, () -> client.reconstructRichForCommit(List.of(new byte[] {0}, new byte[8193])));
            assertThrows(IllegalArgumentException.class, () -> client.reconstructRichForCommit(List.of()));
            mode.set(6);
            assertFalse(client.reconstructRich(List.of()).initialized());
            assertTrue(assertThrows(YjsEngineException.class, () -> client.reconstructRichForCommit(List.of(new byte[] {0}, new byte[] {1}))).rejected());
            mode.set(7);
            assertTrue(client.reconstructRich(List.of()).initialized());
            mode.set(8);
            assertFalse(assertThrows(YjsEngineException.class, () -> client.reconstructRich(List.of())).rejected());
            for (int value : new int[] { 1, 2, 3, 4, 5 }) {
                mode.set(value);
                assertFalse(assertThrows(YjsEngineException.class, () -> client.reconstructRich(List.of())).rejected());
            }
        } finally { server.stop(0); }
    }
    @Test void failsClosedOnRejectionsBadResponsesOversizeAndTimeout() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var mode = new AtomicInteger();
        server.createContext("/v1/reconstruct", exchange -> {
            try {
                int selected = mode.get();
                if (selected == 4) Thread.sleep(1000);
                int status = selected == 0 || selected == 5 || selected == 6 ? 422 : selected == 1 ? 503 : 200;
                byte[] body = (selected == 3 ? "x".repeat(70000) : selected == 2 ? "{}" : selected == 5 ? "{\"code\":\"TOO_LARGE\"}" : selected == 6 ? "not json" : "{\"code\":\"rejected\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            } catch (java.io.IOException ignored) { }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var json = JsonMapper.builder().build();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new YjsEngineClient(base, json, Duration.ofSeconds(2));
            assertTrue(assertThrows(YjsEngineException.class, () -> client.reconstruct(List.of())).rejected());
            mode.set(5);
            assertTrue(assertThrows(YjsEngineException.class, () -> client.reconstruct(List.of())).limitExceeded());
            mode.set(6);
            var malformed = assertThrows(YjsEngineException.class, () -> client.reconstruct(List.of()));
            assertTrue(malformed.rejected()); assertFalse(malformed.limitExceeded());
            for (int value : new int[] { 1, 2, 3 }) {
                mode.set(value);
                assertFalse(assertThrows(YjsEngineException.class, () -> client.reconstruct(List.of())).rejected());
            }
            mode.set(4);
            var shortTimeout = new YjsEngineClient(base, json, Duration.ofMillis(150));
            assertFalse(assertThrows(YjsEngineException.class, () -> shortTimeout.reconstruct(List.of())).rejected());
            assertThrows(IllegalArgumentException.class, () -> new YjsEngineClient("http://example.com:8091", json, Duration.ofSeconds(1)));
            assertThrows(IllegalArgumentException.class, () -> client.reconstruct(List.of(new byte[8193])));
        } finally { server.stop(0); }
    }
}
