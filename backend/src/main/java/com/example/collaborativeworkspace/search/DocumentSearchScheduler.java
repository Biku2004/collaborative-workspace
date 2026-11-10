package com.example.collaborativeworkspace.search;

import com.example.collaborativeworkspace.observability.WorkspaceTelemetry;
import static com.example.collaborativeworkspace.observability.WorkspaceTelemetry.Stage.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
@ConditionalOnProperty(name = "workspace.search.indexing.enabled", havingValue = "true")
public class DocumentSearchScheduler {
    private WorkspaceTelemetry telemetry = WorkspaceTelemetry.disabled();
    @org.springframework.beans.factory.annotation.Autowired
    void observe(WorkspaceTelemetry telemetry) { this.telemetry = telemetry; }
    private final DocumentSearchProjector projector;
    public DocumentSearchScheduler(DocumentSearchProjector projector) { this.projector = projector; }
    @Scheduled(fixedDelayString = "${workspace.search.poll-ms:2000}")
    public void poll() {
        try { telemetry.measure(SEARCH_POLL, projector::runBatch); }
        catch (RuntimeException ex) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Search poll failed: {}", ex.getClass().getName()); }
    }
}
