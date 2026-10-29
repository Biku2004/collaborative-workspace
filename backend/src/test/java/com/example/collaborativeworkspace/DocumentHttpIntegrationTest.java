package com.example.collaborativeworkspace;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;

@EnabledIfEnvironmentVariable(named = "WORKSPACE_RUN_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class DocumentHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        String url = required("WORKSPACE_TEST_DB_URL");
        String developmentUrl = System.getenv("WORKSPACE_DB_URL");
        if (url.equals(developmentUrl)) throw new IllegalStateException("Test and development database URLs must differ");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> required("WORKSPACE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> required("WORKSPACE_TEST_DB_PASSWORD"));
        registry.add("workspace.dev.password", () -> "test-only-password");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set to the dedicated test database configuration");
        return value;
    }

    @Test
    void createsRetrievesAndValidatesDocuments() throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String base = "http://127.0.0.1:" + port;
        UUID createdId = null;
        String userEmail = "lesson-" + UUID.randomUUID() + "@example.com";
        try {
            assertEquals(200, send(client, "GET", base + "/api/status", null).statusCode());
            assertEquals(401, send(client, "GET", base + "/api/documents/00000000-0000-0000-0000-000000000000", null).statusCode());
            var noToken = HttpRequest.newBuilder(URI.create(base + "/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=learner&password=test-only-password")).build();
            assertEquals(403, client.send(noToken, HttpResponse.BodyHandlers.discarding()).statusCode());
            String csrf = token(send(client, "GET", base + "/api/csrf", null).body());
            String userPayload = "{\"email\":\"" + userEmail.toUpperCase(java.util.Locale.ROOT) + "\",\"password\":\"test-only-long-password\"}";
            assertEquals(401, post(client, base + "/api/dev/users", userPayload, csrf).statusCode());
            var wrongLogin = HttpRequest.newBuilder(URI.create(base + "/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("X-CSRF-TOKEN", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString("username=learner&password=incorrect")).build();
            var failure = client.send(wrongLogin, HttpResponse.BodyHandlers.ofString());
            assertEquals(302, failure.statusCode());
            assertTrue(failure.headers().firstValue("Location").orElseThrow().contains("error"));
            assertEquals(401, send(client, "GET", base + "/api/documents/00000000-0000-0000-0000-000000000000", null).statusCode());
            var login = HttpRequest.newBuilder(URI.create(base + "/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-CSRF-TOKEN", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString("username=learner&password=test-only-password"))
                    .build();
            assertEquals(302, client.send(login, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, send(client, "POST", base + "/api/documents", "{\"title\":\"No token\"}").statusCode());
            csrf = token(send(client, "GET", base + "/api/csrf", null).body());
            assertEquals(403, send(client, "POST", base + "/api/dev/users", userPayload).statusCode());
            var first = postRequest(base + "/api/dev/users", userPayload, csrf);
            var second = postRequest(base + "/api/dev/users", userPayload, csrf);
            var requests = java.util.List.of(client.sendAsync(first, HttpResponse.BodyHandlers.ofString()), client.sendAsync(second, HttpResponse.BodyHandlers.ofString()));
            var results = requests.stream().map(java.util.concurrent.CompletableFuture::join).toList();
            assertEquals(java.util.Set.of(201, 409), results.stream().map(HttpResponse::statusCode).collect(java.util.stream.Collectors.toSet()));
            String userResponse = results.stream().filter(r -> r.statusCode() == 201).findFirst().orElseThrow().body();
            assertTrue(userResponse.contains(userEmail));
            assertFalse(userResponse.contains("password"));
            String hash = jdbc.queryForObject("SELECT password_hash FROM app_users WHERE email = ?", String.class, userEmail);
            assertNotEquals("test-only-long-password", hash);
            assertTrue(passwordEncoder.matches("test-only-long-password", hash));
            assertFalse(passwordEncoder.matches("wrong-password", hash));
            assertEquals(400, post(client, base + "/api/dev/users", "{\"email\":\"invalid\",\"password\":\"a-long-enough-password\"}", csrf).statusCode());
            assertEquals(400, post(client, base + "/api/dev/users", "{\"email\":\"valid@example.com\",\"password\":\"short\"}", csrf).statusCode());
            assertEquals(400, post(client, base + "/api/dev/users", "{\"email\":\"valid@example.com\",\"password\":\"" + "é".repeat(40) + "\"}", csrf).statusCode());
            var created = post(client, base + "/api/documents", "{\"title\":\"  Test document  \"}", csrf);
            assertEquals(201, created.statusCode());
            String location = created.headers().firstValue("Location").orElseThrow();
            createdId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
            var fetched = send(client, "GET", base + location, null);
            assertEquals(200, fetched.statusCode());
            assertTrue(fetched.body().contains("Test document"));
            assertEquals(400, post(client, base + "/api/documents", "{\"title\":\" \"}", csrf).statusCode());
            assertEquals(400, post(client, base + "/api/documents", "{\"title\":\"abc\\u0000def\"}", csrf).statusCode());
            assertEquals(400, post(client, base + "/api/documents", "{", csrf).statusCode());
            assertEquals(400, send(client, "GET", base + "/api/documents/not-a-uuid", null).statusCode());
            assertEquals(404, send(client, "GET", base + "/api/documents/00000000-0000-0000-0000-000000000000", null).statusCode());
        } finally {
            if (createdId != null) jdbc.update("DELETE FROM documents WHERE id = ?", createdId);
            jdbc.update("DELETE FROM app_users WHERE email = ?", userEmail);
        }
    }

    private String token(String json) {
        var matcher = java.util.regex.Pattern.compile("\"token\":\"([^\"]+)\"").matcher(json);
        if (!matcher.find()) throw new IllegalStateException("Missing CSRF token");
        return matcher.group(1);
    }

    private HttpResponse<String> post(HttpClient client, String url, String body, String csrf) throws Exception {
        return client.send(postRequest(url, body, csrf), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest postRequest(String url, String body, String csrf) {
        return HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", csrf).POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpResponse<String> send(HttpClient client, String method, String url, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url));
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
