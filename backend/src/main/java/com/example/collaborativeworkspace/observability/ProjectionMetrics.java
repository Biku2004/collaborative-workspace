package com.example.collaborativeworkspace.observability;

import com.example.collaborativeworkspace.collaboration.engine.CollaborationFormatPolicy;
import java.util.Arrays;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scrapes read a cached numeric snapshot, never run SQL or read document content. */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "workspace.observability.projections.enabled", havingValue = "true", matchIfMissing = true)
public class ProjectionMetrics {
    private final JdbcTemplate jdbc;
    private final CollaborationFormatPolicy formats;
    private final WorkspaceTelemetry telemetry;
    private final boolean searchEnabled, relayEnabled;
    private volatile double[] snapshot = unknown();
    private volatile long lastSuccess;
    private static double[] unknown() { double[] values = new double[7]; Arrays.fill(values, Double.NaN); return values; }
    public ProjectionMetrics(JdbcTemplate jdbc, CollaborationFormatPolicy formats, WorkspaceTelemetry telemetry,
            @org.springframework.beans.factory.annotation.Value("${workspace.search.indexing.enabled:false}") boolean searchEnabled,
            @org.springframework.beans.factory.annotation.Value("${workspace.delivery.enabled:true}") boolean deliveryEnabled,
            @org.springframework.beans.factory.annotation.Value("${workspace.delivery.relay-enabled:true}") boolean relayEnabled) {
        this.jdbc = jdbc; this.formats = formats; this.telemetry = telemetry;
        this.searchEnabled = searchEnabled; this.relayEnabled = deliveryEnabled && relayEnabled;
        telemetry.gauge("search.indexing.enabled", this, metrics -> metrics.searchEnabled ? 1 : 0);
        telemetry.gauge("delivery.relay.enabled", this, metrics -> metrics.relayEnabled ? 1 : 0);
        String[] names = {"outbox.pending", "outbox.oldest.seconds", "checkpoint.lag.updates", "search.lag.updates", "search.pending.documents", "search.failed.documents", "checkpoint.pending.documents"};
        for (int index = 0; index < names.length; index++) {
            final int offset = index;
            telemetry.gauge(names[index], this, metrics -> metrics.snapshot[offset]);
        }
        telemetry.gauge("projection.snapshot.age.seconds", this, metrics -> metrics.lastSuccess == 0 ? Double.NaN : (System.nanoTime() - metrics.lastSuccess) / 1_000_000_000.0);
    }
    @Scheduled(fixedDelayString = "${workspace.observability.projections.poll-ms:10000}", initialDelayString = "${workspace.observability.projections.initial-delay-ms:1000}")
    public void refresh() {
        try {
            telemetry.measure(WorkspaceTelemetry.Stage.PROJECTION_POLL, () -> {
                double[] values = jdbc.query(connection -> {
                    var statement = connection.prepareStatement("""
                        SELECT o.pending,o.age,
                          COALESCE(MAX(s.head_sequence-COALESCE(c.through_sequence,0)),0) AS checkpoint_lag,
                          COALESCE(MAX(s.head_sequence-CASE WHEN p.epoch=s.epoch THEN COALESCE(p.through_sequence,0) ELSE 0 END),0) AS search_lag,
                          count(*) FILTER(WHERE s.document_id IS NOT NULL AND (p.epoch IS NULL OR p.epoch<>s.epoch OR p.through_sequence<>s.head_sequence)) AS search_pending,
                          count(*) FILTER(WHERE p.failure_count>0 AND (p.epoch IS NULL OR p.epoch<>s.epoch OR p.through_sequence<>s.head_sequence)) AS search_failed,
                          count(*) FILTER(WHERE s.head_sequence>COALESCE(c.through_sequence,0)) AS checkpoint_pending
                        FROM (SELECT count(*) AS pending,COALESCE(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP-min(created_at)),0) AS age
                              FROM document_update_outbox WHERE published_at IS NULL) o
                        LEFT JOIN (document_streams s JOIN documents d ON d.id=s.document_id AND d.workspace_id IS NOT NULL AND NOT d.archived
                                   AND (d.collaboration_format='PLAIN_TEXT_V1' OR (? AND d.collaboration_format='RICH_TEXT_V1')))
                          ON true
                        LEFT JOIN LATERAL (SELECT through_sequence FROM document_checkpoints WHERE document_id=s.document_id AND epoch=s.epoch
                                           ORDER BY through_sequence DESC LIMIT 1) c ON true
                        LEFT JOIN document_search_projection p ON p.document_id=s.document_id
                        GROUP BY o.pending,o.age
                        """);
                    statement.setBoolean(1, formats.supports("RICH_TEXT_V1"));
                    statement.setQueryTimeout(2);
                    return statement;
                }, rs -> {
                    if (!rs.next()) throw new IllegalStateException("Missing aggregate");
                    return new double[] {rs.getDouble(1), Math.max(0, rs.getDouble(2)), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getDouble(7)};
                });
                snapshot = values;
                lastSuccess = System.nanoTime();
                return null;
            });
        } catch (RuntimeException ignored) { /* Preserve the last snapshot; failure counter and age reveal stale diagnostics. */ }
    }
}
