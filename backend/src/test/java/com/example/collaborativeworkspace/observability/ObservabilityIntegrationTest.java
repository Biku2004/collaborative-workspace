package com.example.collaborativeworkspace.observability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import com.example.collaborativeworkspace.auth.UserRepository;
import com.example.collaborativeworkspace.collaboration.engine.YjsEngineClient;
import com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateService;
import com.example.collaborativeworkspace.documents.DocumentService;
import com.example.collaborativeworkspace.documents.history.DocumentCheckpointService;
import com.example.collaborativeworkspace.search.DocumentSearchProjector;
import com.example.collaborativeworkspace.support.DiagnosticLogCapture;
import com.example.collaborativeworkspace.support.TestDatabaseGuard;
import com.example.collaborativeworkspace.workspaces.WorkspaceService;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@EnabledIfEnvironmentVariable(named = "WORKSPACE_RUN_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ObservabilityIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry metrics;
    @Autowired ProjectionMetrics projections;
    @Autowired UserRepository users;
    @Autowired WorkspaceService workspaces;
    @Autowired DocumentService documents;
    @Autowired DocumentUpdateService updates;
    @Autowired DocumentCheckpointService checkpoints;
    @Autowired DocumentSearchProjector search;
    @Autowired PasswordEncoder encoder;
    @MockitoSpyBean YjsEngineClient engine;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabaseGuard::verifiedUrl);
        registry.add("spring.datasource.username", () -> System.getenv("WORKSPACE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("WORKSPACE_TEST_DB_PASSWORD"));
        registry.add("workspace.dev.password", () -> "test-only-password");
        registry.add("workspace.delivery.enabled", () -> "false");
        registry.add("workspace.observability.projections.initial-delay-ms", () -> "3600000");
    }
    @Test void lagChangesAfterCheckpointAndIndexAndMetricsRequireBootstrapAccess() throws Exception {
        UUID actor = UUID.randomUUID(), workspace = null;
        String email = "diagnostics-" + actor + "@example.com", secret = "private-document-sentinel";
        try (var logs = new DiagnosticLogCapture(WorkspaceTelemetry.class)) {
            users.create(actor, email, encoder.encode("test-user-password"));
            workspace = workspaces.create(email, "Diagnostics").id();
            UUID document = documents.create(workspace, email, "Private title").id();
            projections.refresh();
            double beforeOutbox = gauge("outbox.pending"), beforeIndex = gauge("search.pending.documents"), beforeCheckpoints = gauge("checkpoint.pending.documents");
            byte[] payload = Base64.getDecoder().decode("AQEBAAQBB2NvbnRlbnQKSGVsbG8gSmF2YQA=");
            doReturn(new YjsEngineClient.Candidate(secret, payload)).when(engine).reconstruct(anyList());
            updates.submit(workspace, document, actor, 0, UUID.randomUUID(), payload, () -> true);
            projections.refresh();
            assertEquals(beforeOutbox + 1, gauge("outbox.pending"));
            assertEquals(beforeIndex + 1, gauge("search.pending.documents"));
            assertEquals(beforeCheckpoints + 1, gauge("checkpoint.pending.documents"));
            assertTrue(gauge("checkpoint.lag.updates") >= 1); assertTrue(gauge("search.lag.updates") >= 1);
            assertTrue(gauge("outbox.oldest.seconds") >= 0); assertTrue(gauge("projection.snapshot.age.seconds") < 5);
            checkpoints.create(workspace, document, actor); assertEquals(1, search.runBatch().indexed());
            projections.refresh();
            assertEquals(beforeIndex, gauge("search.pending.documents"));
            assertEquals(beforeCheckpoints, gauge("checkpoint.pending.documents"));
            assertEquals(0, gauge("search.indexing.enabled")); assertEquals(0, gauge("delivery.relay.enabled"));

            HttpClient anonymous = client();
            assertEquals(401, get(anonymous, "/actuator/prometheus").statusCode());
            HttpClient member = client(); login(member, email, "test-user-password");
            assertEquals(403, get(member, "/actuator/prometheus").statusCode());
            assertEquals(403, get(member, "/actuator/metrics").statusCode());
            HttpClient bootstrap = client(); login(bootstrap, "learner", "test-only-password");
            var scrape = get(bootstrap, "/actuator/prometheus"); assertEquals(200, scrape.statusCode());
            assertNotNull(UUID.fromString(scrape.headers().firstValue("X-Correlation-ID").orElseThrow()));
            assertTrue(scrape.body().contains("workspace_operation_seconds_count"));
            assertTrue(scrape.body().contains("workspace_search_pending_documents"));
            for (String sensitive : java.util.List.of(secret, email, "Private title", "test-user-password", Base64.getEncoder().encodeToString(payload))) {
                assertFalse(scrape.body().contains(sensitive));
                assertTrue(logs.events.stream().noneMatch(event -> DiagnosticLogCapture.rendered(event).contains(sensitive)));
            }
            assertEquals(404, get(bootstrap, "/actuator/env").statusCode());
            assertEquals(404, get(bootstrap, "/actuator/configprops").statusCode());
            assertEquals(200, get(bootstrap, "/actuator/health").statusCode());
            assertFalse(get(bootstrap, "/actuator/health").body().contains("components"));
        } finally {
            if (workspace != null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?", workspace); jdbc.update("DELETE FROM workspaces WHERE id=?", workspace); }
            jdbc.update("DELETE FROM app_users WHERE id=?", actor);
        }
    }
    private double gauge(String name) { return metrics.get("workspace." + name).gauge().value(); }
    private HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(); }
    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private void login(HttpClient client, String username, String password) throws Exception {
        var csrf = tools.jackson.databind.json.JsonMapper.builder().build().readTree(get(client, "/api/csrf").body()).path("token").asText();
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded").header("X-CSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString("username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8))).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(302, response.statusCode()); assertFalse(response.headers().firstValue("Location").orElseThrow().contains("error"));
    }
}
