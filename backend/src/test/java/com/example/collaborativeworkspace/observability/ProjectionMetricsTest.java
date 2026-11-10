package com.example.collaborativeworkspace.observability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.example.collaborativeworkspace.collaboration.engine.CollaborationFormatPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;

class ProjectionMetricsTest {
    @Test void failedPollPreservesSnapshotAndReportsItsAgeAndFailure() {
        var registry = new SimpleMeterRegistry();
        var jdbc = mock(JdbcTemplate.class);
        var metrics = new ProjectionMetrics(jdbc, mock(CollaborationFormatPolicy.class), new WorkspaceTelemetry(registry), true, true, true);
        assertTrue(Double.isNaN(registry.get("workspace.outbox.pending").gauge().value()));
        doReturn(new double[] {3, 7, 2, 1, 1, 0, 2}).doThrow(new IllegalStateException("sensitive database error"))
                .when(jdbc).query(any(PreparedStatementCreator.class), any(ResultSetExtractor.class));
        metrics.refresh(); metrics.refresh();
        assertEquals(3, registry.get("workspace.outbox.pending").gauge().value());
        assertTrue(registry.get("workspace.projection.snapshot.age.seconds").gauge().value() >= 0);
        assertEquals(1, registry.get("workspace.operation").tags("stage", "projection_poll", "outcome", "failed").timer().count());
    }
}
