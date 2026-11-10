package com.example.collaborativeworkspace.search;

import com.example.collaborativeworkspace.observability.WorkspaceTelemetry;
import static com.example.collaborativeworkspace.observability.WorkspaceTelemetry.Stage.*;

import com.example.collaborativeworkspace.collaboration.engine.CollaborationFormatPolicy;
import com.example.collaborativeworkspace.collaboration.engine.YjsEngineClient;
import com.example.collaborativeworkspace.collaboration.engine.YjsEngineException;
import com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateRepository;
import com.example.collaborativeworkspace.common.crypto.ContentDigest;
import com.example.collaborativeworkspace.documents.history.DocumentSnapshotBudget;
import com.example.collaborativeworkspace.workspaces.WorkspaceRepository;

import java.util.Arrays;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs independently of update acceptance. Every candidate is discarded unless its captured head still matches. */
@Service
@Profile("dev")
public class DocumentSearchProjector {
    private WorkspaceTelemetry telemetry = WorkspaceTelemetry.disabled();
    @org.springframework.beans.factory.annotation.Autowired
    void observe(WorkspaceTelemetry telemetry) { this.telemetry = telemetry; }
    public record Batch(int attempted, int indexed, int failed, boolean busy) {}
    private final DocumentSearchRepository search;
    private final YjsEngineClient engine;
    private final CollaborationFormatPolicy formats;
    private final WorkspaceRepository workspaces;
    private final DocumentUpdateRepository updates;
    private final DocumentSnapshotBudget jobs;
    private final TransactionTemplate transaction;
    public DocumentSearchProjector(DocumentSearchRepository search, YjsEngineClient engine, CollaborationFormatPolicy formats, WorkspaceRepository workspaces,
            DocumentUpdateRepository updates, DocumentSnapshotBudget jobs, PlatformTransactionManager manager) {
        this.search = search; this.engine = engine; this.formats = formats; this.workspaces = workspaces; this.updates = updates; this.jobs = jobs;
        transaction = new TransactionTemplate(manager);
    }
    public Batch runBatch() {
        int attempted = 0, indexed = 0, failed = 0;
        for (var source : search.pending(formats.supports("RICH_TEXT_V1"), 4)) {
            if (!jobs.acquire()) return new Batch(attempted, indexed, failed, true);
            attempted++;
            try { if (telemetry.measure(SEARCH_INDEX, () -> index(source))) indexed++; }
            catch (RuntimeException ex) {
                failed++;
                String code = ex instanceof YjsEngineException engineFailure ? (engineFailure.rejected() ? "SOURCE_REJECTED" : "ENGINE_UNAVAILABLE") : "INDEX_UNAVAILABLE";
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Search projection failed: {}", code);
                try { search.failed(source, code); }
                catch (RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Search retry bookkeeping failed: {}", failure.getClass().getName()); }
            } finally { jobs.release(); }
        }
        return new Batch(attempted, indexed, failed, false);
    }
    boolean index(DocumentSearchRepository.Source source) {
        if (!formats.supports(source.format())) return false;
        var candidate = engine.materialize(source.format(), source.state());
        if (!Arrays.equals(source.state(), candidate.checkpoint())) throw new YjsEngineException(true);
        // PostgreSQL text cannot contain NUL. Replacing it separates tokens without changing the authoritative Yjs state.
        String content = candidate.text().replace('\0', ' ');
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!workspaces.lockWorkspace(source.workspaceId()) || !updates.lockDocument(source.workspaceId(), source.documentId())) return false;
            var current = search.current(source.workspaceId(), source.documentId());
            if (current.isEmpty()) return false;
            var head = current.get();
            if (head.epoch() != source.epoch() || head.sequence() != source.sequence() || !head.format().equals(source.format()) || !Arrays.equals(head.state(), source.state())) return false;
            search.publish(source, content, ContentDigest.sha256(source.state())); return true;
        }));
    }
}
