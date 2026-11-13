package com.example.collaborativeworkspace.observability;

import com.example.collaborativeworkspace.collaboration.engine.YjsEngineException;
import com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Allowlisted diagnostics only: never accepts content, URLs, credentials or exception messages. */
@Component
public class WorkspaceTelemetry {
    public enum Stage { ACCEPT, VALIDATE, COMMIT, RELAY, SOCKET_SEND, SYNC, ENGINE, SEARCH_INDEX, DELIVERY_POLL, SEARCH_POLL, PROJECTION_POLL, CLUSTER_PUBLISH, CLUSTER_RECEIVE }
    public enum Signal { CONNECTION_REJECTED, QUEUE_OVERFLOW, EXECUTOR_REJECTED, PRESENCE_DROPPED, SOCKET_DENIED, SOCKET_FAILED, SOCKET_CLOSED, CLUSTER_UNAVAILABLE, CLUSTER_INVALID, CLUSTER_DROPPED }
    private static final java.util.Set<String> SAFE_CODES = java.util.Set.of("INVALID_UPDATE", "ACCESS_DENIED", "NOT_FOUND", "SCHEMA_UNSUPPORTED", "RETRY_HEAD_CHANGED", "EPOCH_CHANGED", "DOCUMENT_ARCHIVED", "UPDATE_ID_CONFLICT");
    private static final WorkspaceTelemetry DISABLED = new WorkspaceTelemetry(null);
    private final MeterRegistry registry;
    private final ThreadLocal<UUID> trace = new ThreadLocal<>();
    public WorkspaceTelemetry(MeterRegistry registry) { this.registry = registry; }
    public static WorkspaceTelemetry disabled() { return DISABLED; }
    public UUID traceId() { UUID current = trace.get(); return current == null ? UUID.randomUUID() : current; }
    public Context context(UUID id) { return new Context(id); }
    public final class Context implements AutoCloseable {
        private final UUID previous = trace.get();
        private Context(UUID id) { trace.set(id); }
        @Override public void close() { if (previous == null) trace.remove(); else trace.set(previous); }
    }
    public void request(int status) {
        try { if (registry != null) registry.counter("workspace.http.requests", "status", Integer.toString(Math.max(100, Math.min(599, status)))).increment(); }
        catch (RuntimeException ignored) { /* Preserve the HTTP result. */ }
    }
    public <T> T measure(Stage stage, Supplier<T> action) { return measure(stage, traceId(), action); }
    public <T> T measure(Stage stage, UUID traceId, Supplier<T> action) {
        if (registry == null) return action.get();
        UUID previous = trace.get();
        trace.set(traceId);
        long started = System.nanoTime();
        String outcome = "failed";
        String reason = "UNAVAILABLE";
        try { T result = action.get(); outcome = "success"; reason = "OK"; return result; }
        catch (RuntimeException ex) {
            outcome = ex instanceof DocumentUpdateException || ex instanceof YjsEngineException engine && engine.rejected() ? "rejected" : "failed";
            reason = ex instanceof DocumentUpdateException update && update.code() != null && SAFE_CODES.contains(update.code()) ? update.code()
                    : ex instanceof YjsEngineException engine ? (engine.rejected() ? "ENGINE_REJECTED" : "ENGINE_UNAVAILABLE") : "UNAVAILABLE";
            throw ex;
        } finally {
            long elapsed = System.nanoTime() - started;
            String phase = stage.name().toLowerCase(java.util.Locale.ROOT);
            try {
                Timer.builder("workspace.operation").tag("stage", phase).tag("outcome", outcome)
                        .publishPercentileHistogram().register(registry).record(elapsed, TimeUnit.NANOSECONDS);
                // Poll success is available as metrics; only work and poll failures produce logs.
                boolean poll = stage == Stage.DELIVERY_POLL || stage == Stage.SEARCH_POLL || stage == Stage.PROJECTION_POLL;
                if (!poll || !"success".equals(outcome))
                    LoggerFactory.getLogger(WorkspaceTelemetry.class).atLevel("failed".equals(outcome) ? org.slf4j.event.Level.WARN : org.slf4j.event.Level.INFO)
                            .addKeyValue("event", "workspace_operation")
                            .addKeyValue("correlationId", traceId).addKeyValue("stage", phase).addKeyValue("outcome", outcome).addKeyValue("reason", reason)
                            .addKeyValue("durationMs", elapsed / 1_000_000.0).log("Workspace operation");
            } catch (RuntimeException ignored) { /* Diagnostics must never change a committed operation's result. */ }
            finally { if (previous == null) trace.remove(); else trace.set(previous); }
        }
    }
    public void signal(Signal signal) {
        try { if (registry != null) registry.counter("workspace.transport", "signal", signal.name().toLowerCase(java.util.Locale.ROOT)).increment(); }
        catch (RuntimeException ignored) { /* A metrics backend failure must not affect transport. */ }
    }
    public <T> void gauge(String name, T state, ToDoubleFunction<T> value) {
        if (registry != null) registry.gauge("workspace." + name, state, value);
    }
}
