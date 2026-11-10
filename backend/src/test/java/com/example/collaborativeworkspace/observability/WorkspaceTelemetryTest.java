package com.example.collaborativeworkspace.observability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.collaborativeworkspace.support.DiagnosticLogCapture;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WorkspaceTelemetryTest {
    @Test void nestedStagesShareCorrelationRestoreContextAndNeverRecordExceptionData() {
        var registry = new SimpleMeterRegistry(); var telemetry = new WorkspaceTelemetry(registry);
        UUID id = UUID.randomUUID();
        String secret = "password=secret; JSESSIONID=session-secret; private document contents";
        try (var logs = new DiagnosticLogCapture(WorkspaceTelemetry.class)) {
            assertThrows(IllegalStateException.class, () -> telemetry.measure(WorkspaceTelemetry.Stage.ACCEPT, id,
                    () -> telemetry.measure(WorkspaceTelemetry.Stage.COMMIT, () -> { throw new IllegalStateException(secret); })));
            assertTrue(logs.contains(id, "accept")); assertTrue(logs.contains(id, "commit"));
            for (var event : logs.events) {
                assertNull(event.getThrowableProxy()); assertFalse(DiagnosticLogCapture.rendered(event).contains(secret));
                assertEquals("failed", DiagnosticLogCapture.field(event, "outcome"));
            }
            assertNotEquals(id, telemetry.traceId());
            assertEquals(1, registry.get("workspace.operation").tags("stage", "commit", "outcome", "failed").timer().count());
            assertThrows(AssertionError.class, () -> telemetry.measure(WorkspaceTelemetry.Stage.VALIDATE, () -> { throw new AssertionError(secret); }));
            assertEquals(1, registry.get("workspace.operation").tags("stage", "validate", "outcome", "failed").timer().count());
            var untrusted = new com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateException(secret);
            assertSame(untrusted, assertThrows(RuntimeException.class, () -> telemetry.measure(WorkspaceTelemetry.Stage.SYNC, () -> { throw untrusted; })));
            assertFalse(DiagnosticLogCapture.rendered(logs.events.getLast()).contains(secret));
            var missing = new com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateException(null);
            assertSame(missing, assertThrows(RuntimeException.class, () -> telemetry.measure(WorkspaceTelemetry.Stage.SYNC, () -> { throw missing; })));
            assertTrue(registry.getMeters().stream().allMatch(meter -> meter.getId().getTags().stream().allMatch(tag -> tag.getKey().equals("stage") || tag.getKey().equals("outcome"))));
        }
    }
    @Test void metricsFailureCannotReplaceSuccessfulCommitOrOriginalFailure() {
        var registry = mock(MeterRegistry.class);
        when(registry.config()).thenThrow(new IllegalStateException("exporter unavailable"));
        var telemetry = new WorkspaceTelemetry(registry);
        assertEquals("committed", telemetry.measure(WorkspaceTelemetry.Stage.COMMIT, () -> "committed"));
        var failure = new IllegalArgumentException("original");
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> telemetry.measure(WorkspaceTelemetry.Stage.COMMIT, () -> { throw failure; })));
    }
    @Test void httpDiagnosticsIgnoreIncomingHeadersBodiesQueryAndCredentials() throws Exception {
        var telemetry = new WorkspaceTelemetry(new SimpleMeterRegistry());
        var filter = new RequestDiagnosticsFilter(telemetry);
        var request = new MockHttpServletRequest("POST", "/login");
        String sentinel = "sensitive-diagnostic-sentinel";
        request.setQueryString("q=" + sentinel); request.addHeader("X-Correlation-ID", sentinel);
        request.addHeader("Cookie", "JSESSIONID=" + sentinel); request.addHeader("Authorization", sentinel);
        request.setContent(("password=" + sentinel).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        try (var logs = new DiagnosticLogCapture(RequestDiagnosticsFilter.class)) {
            filter.doFilter(request, response, (req, res) -> { response.setStatus(403); assertEquals(response.getHeader("X-Correlation-ID"), telemetry.traceId().toString()); });
            UUID.fromString(response.getHeader("X-Correlation-ID"));
            assertEquals(1, logs.events.size());
            var event = logs.events.getFirst();
            assertFalse(DiagnosticLogCapture.rendered(event).contains(sentinel)); assertNull(event.getThrowableProxy());
            assertEquals("403", DiagnosticLogCapture.field(event, "status"));
            assertNull(org.slf4j.MDC.get("requestCorrelationId"));
            assertNotEquals(response.getHeader("X-Correlation-ID"), telemetry.traceId().toString());
        }
    }
}
