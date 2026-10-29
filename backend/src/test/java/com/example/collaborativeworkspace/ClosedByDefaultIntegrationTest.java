package com.example.collaborativeworkspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "WORKSPACE_RUN_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClosedByDefaultIntegrationTest {
    @LocalServerPort int port;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        String url = System.getenv("WORKSPACE_TEST_DB_URL");
        if (url == null || url.isBlank() || url.equals(System.getenv("WORKSPACE_DB_URL"))) {
            throw new IllegalStateException("A separate test database URL is required");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("WORKSPACE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("WORKSPACE_TEST_DB_PASSWORD"));
    }

    @Test
    void onlyStatusIsPublicWithoutDevelopmentProfile() throws Exception {
        var client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port;
        assertEquals(200, get(client, base + "/api/status"));
        assertEquals(403, get(client, base + "/api/documents/00000000-0000-0000-0000-000000000000"));
        assertEquals(403, get(client, base + "/api/csrf"));
    }

    private int get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
