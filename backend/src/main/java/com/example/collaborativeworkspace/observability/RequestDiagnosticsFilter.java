package com.example.collaborativeworkspace.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs before security, including login failures. Incoming correlation headers are never trusted or logged. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestDiagnosticsFilter extends OncePerRequestFilter {
    private final WorkspaceTelemetry telemetry;
    public RequestDiagnosticsFilter(WorkspaceTelemetry telemetry) { this.telemetry = telemetry; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID id = UUID.randomUUID();
        response.setHeader("X-Correlation-ID", id.toString());
        long started = System.nanoTime();
        boolean completed = false;
        try (var context = telemetry.context(id); var ignored = MDC.putCloseable("requestCorrelationId", id.toString())) {
            try { chain.doFilter(request, response); completed = true; }
            finally {
                int status = completed ? response.getStatus() : 500;
                telemetry.request(status);
                try {
                // Deliberately omit method, path, query, headers, body, principal and exception data.
                LoggerFactory.getLogger(RequestDiagnosticsFilter.class).atInfo().addKeyValue("event", "http_request")
                        .addKeyValue("correlationId", id).addKeyValue("status", status)
                        .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000.0).log("HTTP request completed");
                } catch (RuntimeException diagnosticFailure) { /* Diagnostics must preserve the response. */ }
            }
        }
    }
}
