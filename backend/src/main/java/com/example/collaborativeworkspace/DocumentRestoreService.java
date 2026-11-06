package com.example.collaborativeworkspace;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("dev")
public class DocumentRestoreService {
    public record Baseline(long epoch, long head, String format, String checkpoint, String sha256) {}
    private final DocumentUpdateRepository updates;
    private final DocumentRevisionService revisions;
    private final DocumentCheckpointRepository checkpoints;
    private final DocumentRestoreRepository restores;
    private final WorkspaceRepository workspaces;
    private final CollaborationFormatPolicy formats;
    private final YjsEngineClient engine;
    private final DocumentSnapshotBudget jobs;
    private final TransactionTemplate transaction;
    public DocumentRestoreService(DocumentUpdateRepository updates, DocumentRevisionService revisions, DocumentCheckpointRepository checkpoints,
            DocumentRestoreRepository restores, WorkspaceRepository workspaces, CollaborationFormatPolicy formats, YjsEngineClient engine,
            DocumentSnapshotBudget jobs, PlatformTransactionManager manager) {
        this.updates = updates; this.revisions = revisions; this.checkpoints = checkpoints; this.restores = restores;
        this.workspaces = workspaces; this.formats = formats; this.engine = engine; this.jobs = jobs; this.transaction = new TransactionTemplate(manager);
    }
    public DocumentRestoreRepository.Restored restore(UUID workspace, UUID document, UUID actor, RestoreRevisionRequest request, BooleanSupplier sessionValid) {
        if (request == null || request.revisionId() == null || request.requestId() == null || request.expectedEpoch() == null || request.expectedHead() == null ||
                request.expectedEpoch() < 0 || request.expectedEpoch() == Long.MAX_VALUE || request.expectedHead() < 0) throw new InvalidUserInputException();
        var captured = accessible(workspace, document, actor, true);
        if (!sessionValid.getAsBoolean()) throw new RevisionException("ACCESS_DENIED");
        var retry = restores.retry(document, actor, request.requestId());
        if (retry.isPresent()) return retry(retry.get(), request);
        expected(captured, request);
        var revision = revisions.preview(workspace, document, actor, request.revisionId());
        if (!captured.collaborationFormat().equals(revision.summary().format())) throw new RevisionException("SCHEMA_UNSUPPORTED");
        if (!jobs.acquire()) throw new RevisionException("REVISION_BUSY");
        try {
            byte[] fresh = engine.freshState(captured.collaborationFormat(), revision.bytes());
            return java.util.Objects.requireNonNull(transaction.execute(status -> {
                // Same workspace -> document order as writers and membership changes.
                if (!workspaces.lockWorkspace(workspace) || !updates.lockDocument(workspace, document)) throw new RevisionException("NOT_FOUND");
                if (!sessionValid.getAsBoolean()) throw new RevisionException("ACCESS_DENIED");
                var current = accessible(workspace, document, actor, true);
                var duplicate = restores.retry(document, actor, request.requestId());
                if (duplicate.isPresent()) return retry(duplicate.get(), request);
                expected(current, request);
                if (!current.collaborationFormat().equals(captured.collaborationFormat())) throw new RevisionException("SCHEMA_UNSUPPORTED");
                updates.ensureStream(document);
                var result = restores.commit(document, actor, request, fresh);
                if (!checkpoints.insert(new DocumentCheckpointRepository.Checkpoint(document, result.epoch(), 0, current.collaborationFormat(), fresh, hash(fresh))))
                    throw new RevisionException("REVISION_INVALID");
                return result;
            }));
        } catch (YjsEngineException ex) {
            if (ex.rejected()) throw new RevisionException("REVISION_INVALID");
            throw ex;
        } finally { jobs.release(); }
    }
    /** The immutable sequence-zero baseline is fetched over bounded HTTP, never as an oversized socket edit. */
    public Baseline baseline(UUID workspace, UUID document, UUID actor) {
        var head = accessible(workspace, document, actor, false);
        byte[] state = DocumentUpdateRepository.EMPTY_CHECKPOINT;
        if (head.epoch() > 0) {
            var baseline = checkpoints.latest(document, head.epoch(), 0).orElseThrow(() -> new RevisionException("REVISION_INVALID"));
            if (!baseline.format().equals(head.collaborationFormat()) || !MessageDigest.isEqual(hash(baseline.bytes()), baseline.digest())) throw new RevisionException("REVISION_INVALID");
            state = baseline.bytes();
        }
        return new Baseline(head.epoch(), head.sequence(), head.collaborationFormat(), java.util.Base64.getEncoder().encodeToString(state), java.util.Base64.getEncoder().encodeToString(hash(state)));
    }
    private DocumentUpdateRepository.Snapshot accessible(UUID workspace, UUID document, UUID actor, boolean write) {
        var head = updates.findAccessible(workspace, document, actor).orElseThrow(() -> new RevisionException("NOT_FOUND"));
        if (head.archived()) throw new RevisionException("NOT_FOUND");
        if (!formats.supports(head.collaborationFormat())) throw new RevisionException("SCHEMA_UNSUPPORTED");
        if (write && head.role() == WorkspaceRole.VIEWER) throw new RevisionException("ACCESS_DENIED");
        return head;
    }
    private void expected(DocumentUpdateRepository.Snapshot head, RestoreRevisionRequest request) {
        if (head.epoch() != request.expectedEpoch()) throw new RevisionException("EPOCH_CHANGED");
        if (head.sequence() != request.expectedHead()) throw new RevisionException("HEAD_CHANGED");
    }
    private DocumentRestoreRepository.Restored retry(DocumentRestoreRepository.Restored result, RestoreRevisionRequest request) {
        if (!result.revisionId().equals(request.revisionId()) || result.fromEpoch() != request.expectedEpoch() || result.fromSequence() != request.expectedHead())
            throw new RevisionException("REVISION_ID_CONFLICT");
        return result;
    }
    static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
}
