package com.example.collaborativeworkspace;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "WORKSPACE_RUN_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocumentHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        String url = required("WORKSPACE_TEST_DB_URL");
        String developmentUrl = System.getenv("WORKSPACE_DB_URL");
        if (url.equals(developmentUrl)) throw new IllegalStateException("Test and development database URLs must differ");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> required("WORKSPACE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> required("WORKSPACE_TEST_DB_PASSWORD"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set to the dedicated test database configuration");
        return value;
    }

    @Test
    void createsRetrievesAndValidatesDocuments() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port;
        UUID createdId = null;
        try {
            assertEquals(200, send(client, "GET", base + "/api/status", null).statusCode());
            var created = send(client, "POST", base + "/api/documents", "{\"title\":\"  Test document  \"}");
            assertEquals(201, created.statusCode());
            String location = created.headers().firstValue("Location").orElseThrow();
            createdId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
            var fetched = send(client, "GET", base + location, null);
            assertEquals(200, fetched.statusCode());
            assertTrue(fetched.body().contains("Test document"));
            assertEquals(400, send(client, "POST", base + "/api/documents", "{\"title\":\" \"}").statusCode());
            assertEquals(400, send(client, "POST", base + "/api/documents", "{").statusCode());
            assertEquals(400, send(client, "GET", base + "/api/documents/not-a-uuid", null).statusCode());
            assertEquals(404, send(client, "GET", base + "/api/documents/00000000-0000-0000-0000-000000000000", null).statusCode());
        } finally {
            if (createdId != null) jdbc.update("DELETE FROM documents WHERE id = ?", createdId);
        }
    }

    private HttpResponse<String> send(HttpClient client, String method, String url, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url));
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
