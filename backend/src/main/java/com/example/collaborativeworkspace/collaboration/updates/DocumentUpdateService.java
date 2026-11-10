package com.example.collaborativeworkspace.collaboration.updates;

import com.example.collaborativeworkspace.observability.WorkspaceTelemetry;
import static com.example.collaborativeworkspace.observability.WorkspaceTelemetry.Stage.*;

import com.example.collaborativeworkspace.collaboration.engine.CollaborationFormatPolicy;
import com.example.collaborativeworkspace.collaboration.engine.YjsEngineClient;
import com.example.collaborativeworkspace.workspaces.WorkspaceRepository;
import com.example.collaborativeworkspace.workspaces.WorkspaceRole;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Speculative engine validation happens outside the transaction; SAVED is returned only after commit. */
@Service
@Profile("dev")
public class DocumentUpdateService {
    private WorkspaceTelemetry telemetry = WorkspaceTelemetry.disabled();
    @org.springframework.beans.factory.annotation.Autowired
    void observe(WorkspaceTelemetry telemetry) { this.telemetry = telemetry; }
    private final DocumentUpdateRepository updates;
    private final WorkspaceRepository workspaces;
    private final YjsEngineClient engine;
    private final CollaborationFormatPolicy formats;
    private final TransactionTemplate transaction;

    public record Saved(UUID documentId, long epoch, long sequence, UUID updateId, boolean duplicate) {}

    public DocumentUpdateService(DocumentUpdateRepository updates, WorkspaceRepository workspaces, YjsEngineClient engine, CollaborationFormatPolicy formats,
            PlatformTransactionManager transactionManager) {
        this.updates = updates; this.workspaces = workspaces; this.engine = engine; this.formats = formats;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Saved submit(UUID workspace, UUID document, UUID actor, long epoch, UUID updateId, byte[] input, BooleanSupplier sessionValid) {
        return submit(workspace, document, actor, epoch, updateId, input, sessionValid, sessionValid);
    }

    public Saved submit(UUID workspace, UUID document, UUID actor, long epoch, UUID updateId, byte[] input,
            Object sessionGuard, BooleanSupplier sessionValid) {
        return telemetry.measure(ACCEPT, () -> submitMeasured(workspace, document, actor, epoch, updateId, input, sessionGuard, sessionValid));
    }

    private Saved submitMeasured(UUID workspace, UUID document, UUID actor, long epoch, UUID updateId, byte[] input,
            Object sessionGuard, BooleanSupplier sessionValid) {
        Objects.requireNonNull(workspace); Objects.requireNonNull(document); Objects.requireNonNull(actor);
        Objects.requireNonNull(updateId); Objects.requireNonNull(sessionGuard); Objects.requireNonNull(sessionValid);
        if (epoch < 0 || input == null || input.length == 0 || input.length > 8192) throw new DocumentUpdateException("INVALID_UPDATE");
        byte[] payload = input.clone();
        byte[] digest;
        try { digest = MessageDigest.getInstance("SHA-256").digest(payload); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }

        for (int attempt = 0; attempt < 3; attempt++) {
            if (!sessionValid.getAsBoolean()) throw new DocumentUpdateException("ACCESS_DENIED");
            var base = accessible(workspace, document, actor);
            requireWritable(base, epoch);
            var existing = updates.findUpdate(document, epoch, updateId);
            if (existing.isPresent()) return verifyDuplicate(document, epoch, updateId, actor, payload, existing.get());

            // This checkpoint and candidate are speculative: do not write or broadcast them yet.
            byte[] candidate = telemetry.measure(VALIDATE, () -> "RICH_TEXT_V1".equals(base.collaborationFormat())
                    ? engine.reconstructRichForCommit(List.of(base.checkpoint(), payload)).checkpoint()
                    : engine.reconstruct(List.of(base.checkpoint(), payload)).checkpoint());
            try {
                Saved result;
                synchronized (sessionGuard) {
                    result = telemetry.measure(COMMIT, () -> transaction.execute(status -> {
                        if (!workspaces.lockWorkspace(workspace) || !sessionValid.getAsBoolean() ||
                                workspaces.memberRole(workspace, actor).filter(role -> role != WorkspaceRole.VIEWER).isEmpty())
                            throw new DocumentUpdateException("ACCESS_DENIED");
                        if (!updates.lockDocument(workspace, document)) throw new DocumentUpdateException("NOT_FOUND");
                        updates.ensureStream(document);
                        var current = accessible(workspace, document, actor);
                        requireWritable(current, epoch);
                        if (!current.collaborationFormat().equals(base.collaborationFormat())) throw new DocumentUpdateException("SCHEMA_UNSUPPORTED");
                        var duplicate = updates.findUpdate(document, epoch, updateId);
                        if (duplicate.isPresent()) return verifyDuplicate(document, epoch, updateId, actor, payload, duplicate.get());
                        if (current.sequence() != base.sequence() || !Arrays.equals(current.checkpoint(), base.checkpoint()))
                            throw new DocumentUpdateException("RETRY_HEAD_CHANGED");
                        long sequence = Math.addExact(current.sequence(), 1);
                        updates.saveUpdate(document, epoch, sequence, updateId, actor, payload, digest);
                        updates.saveHead(document, epoch, sequence, candidate);
                        updates.insertOutbox(document, epoch, sequence, telemetry.traceId());
                        return new Saved(document, epoch, sequence, updateId, false);
                    }));
                }
                return Objects.requireNonNull(result); // execute returns only after the transaction commits.
            } catch (DocumentUpdateException ex) {
                if (!ex.code().equals("RETRY_HEAD_CHANGED") || attempt == 2) throw ex;
            }
        }
        throw new DocumentUpdateException("RETRY_HEAD_CHANGED");
    }

    private DocumentUpdateRepository.Snapshot accessible(UUID workspace, UUID document, UUID actor) {
        return updates.findAccessible(workspace, document, actor).orElseThrow(() -> new DocumentUpdateException("NOT_FOUND"));
    }
    private void requireWritable(DocumentUpdateRepository.Snapshot snapshot, long epoch) {
        if (!formats.supports(snapshot.collaborationFormat())) throw new DocumentUpdateException("SCHEMA_UNSUPPORTED");
        if (snapshot.role() == WorkspaceRole.VIEWER) throw new DocumentUpdateException("ACCESS_DENIED");
        if (snapshot.archived()) throw new DocumentUpdateException("DOCUMENT_ARCHIVED");
        if (snapshot.epoch() != epoch) throw new DocumentUpdateException("EPOCH_CHANGED");
    }
    private Saved verifyDuplicate(UUID document, long epoch, UUID id, UUID actor, byte[] payload, DocumentUpdateRepository.Existing existing) {
        if (!existing.actor().equals(actor) || !Arrays.equals(existing.payload(), payload)) throw new DocumentUpdateException("UPDATE_ID_CONFLICT");
        return new Saved(document, epoch, existing.sequence(), id, true);
    }
}
