package com.example.collaborativeworkspace;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@EnabledIfEnvironmentVariable(named = "WORKSPACE_RUN_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(SessionLifecycleIntegrationTest.TestEndpoints.class)
class SessionLifecycleIntegrationTest {
    @LocalServerPort int port;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        String url = required("WORKSPACE_TEST_DB_URL");
        if (url.equals(System.getenv("WORKSPACE_DB_URL"))) throw new IllegalStateException("A separate test database is required");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> required("WORKSPACE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> required("WORKSPACE_TEST_DB_PASSWORD"));
        registry.add("workspace.dev.password", () -> "test-only-password");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    @TestConfiguration
    static class TestEndpoints {
        @Bean SessionTestController sessionTestController() { return new SessionTestController(); }
    }

    @RestController
    static class SessionTestController {
        @GetMapping("/test/session-interval")
        int interval(HttpServletRequest request) { return request.getSession(false).getMaxInactiveInterval(); }
        @GetMapping("/test/expire-session")
        void setShortTestTimeout(HttpServletRequest request) { request.getSession(false).setMaxInactiveInterval(1); }
    }

    @Test
    void logoutInvalidatesOnlyTheCurrentSessionAndRequiresCsrf() throws Exception {
        String base = "http://127.0.0.1:" + port;
        var cookies1 = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient first = HttpClient.newBuilder().cookieHandler(cookies1).build();
        HttpClient second = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        authenticate(first, base);
        authenticate(second, base);
        assertEquals("1800", get(first, base + "/test/session-interval").body());
        assertEquals(200, get(first, base + "/api/me").statusCode());
        assertEquals(200, get(second, base + "/api/me").statusCode());
        assertEquals(403, postLogout(first, base, null).statusCode());
        assertEquals(200, get(first, base + "/api/me").statusCode());

        String csrf = token(get(first, base + "/api/csrf").body());
        String oldSession = cookies1.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();
        var logout = postLogout(first, base, csrf);
        assertEquals(302, logout.statusCode());
        assertTrue(logout.headers().firstValue("Location").orElseThrow().contains("logout"));
        assertEquals(401, get(first, base + "/api/me").statusCode());
        var replay = HttpRequest.newBuilder(URI.create(base + "/api/me"))
                .header("Cookie", "JSESSIONID=" + oldSession).GET().build();
        assertEquals(401, HttpClient.newHttpClient().send(replay, HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(200, get(second, base + "/api/me").statusCode());
    }

    @Test
    void inactiveSessionExpires() throws Exception {
        String base = "http://127.0.0.1:" + port;
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        authenticate(client, base);
        assertEquals(200, get(client, base + "/test/expire-session").statusCode());
        Thread.sleep(2200); // A test-only short timeout; the real development timeout is 30 minutes.
        assertEquals(401, get(client, base + "/api/me").statusCode());
    }

    private void authenticate(HttpClient client, String base) throws Exception {
        String csrf = token(get(client, base + "/api/csrf").body());
        var request = HttpRequest.newBuilder(URI.create(base + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-CSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString("username=learner&password=test-only-password")).build();
        assertEquals(302, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200, get(client, base + "/api/me").statusCode());
    }

    private HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postLogout(HttpClient client, String base, String csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + "/logout")).POST(HttpRequest.BodyPublishers.noBody());
        if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token(String json) {
        var matcher = java.util.regex.Pattern.compile("\"token\":\"([^\"]+)\"").matcher(json);
        if (!matcher.find()) throw new IllegalStateException("Missing CSRF token");
        return matcher.group(1);
    }
}
