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
    @Test void failsClosedOnRejectionsBadResponsesOversizeAndTimeout() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var mode = new AtomicInteger();
        server.createContext("/v1/reconstruct", exchange -> {
            try {
                int selected = mode.get();
                if (selected == 4) Thread.sleep(1000);
                int status = selected == 0 ? 422 : selected == 1 ? 503 : 200;
                byte[] body = (selected == 3 ? "x".repeat(70000) : selected == 2 ? "{}" : "{\"code\":\"rejected\"}").getBytes(StandardCharsets.UTF_8);
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
