package com.example.collaborativeworkspace;

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
    private final DocumentUpdateRepository updates;
    private final WorkspaceRepository workspaces;
    private final YjsEngineClient engine;
    private final TransactionTemplate transaction;

    public record Saved(UUID documentId, long epoch, long sequence, UUID updateId, boolean duplicate) {}

    public DocumentUpdateService(DocumentUpdateRepository updates, WorkspaceRepository workspaces, YjsEngineClient engine,
            PlatformTransactionManager transactionManager) {
        this.updates = updates; this.workspaces = workspaces; this.engine = engine;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Saved submit(UUID workspace, UUID document, UUID actor, long epoch, UUID updateId, byte[] input, BooleanSupplier sessionValid) {
        return submit(workspace, document, actor, epoch, updateId, input, sessionValid, sessionValid);
    }

    public Saved submit(UUID workspace, UUID document, UUID actor, long epoch, UUID updateId, byte[] input,
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
            var candidate = engine.reconstruct(List.of(base.checkpoint(), payload));
            try {
                Saved result;
                synchronized (sessionGuard) {
                    result = transaction.execute(status -> {
                    if (!workspaces.lockWorkspace(workspace) || !sessionValid.getAsBoolean() ||
                            workspaces.memberRole(workspace, actor).filter(role -> role != WorkspaceRole.VIEWER).isEmpty())
                        throw new DocumentUpdateException("ACCESS_DENIED");
                    if (!updates.lockDocument(workspace, document)) throw new DocumentUpdateException("NOT_FOUND");
                    updates.ensureStream(document);
                    var current = accessible(workspace, document, actor);
                    requireWritable(current, epoch);
                    var duplicate = updates.findUpdate(document, epoch, updateId);
                    if (duplicate.isPresent()) return verifyDuplicate(document, epoch, updateId, actor, payload, duplicate.get());
                    if (current.sequence() != base.sequence() || !Arrays.equals(current.checkpoint(), base.checkpoint()))
                        throw new DocumentUpdateException("RETRY_HEAD_CHANGED");
                    long sequence = Math.addExact(current.sequence(), 1);
                    updates.saveUpdate(document, epoch, sequence, updateId, actor, payload, digest);
                    updates.saveHead(document, epoch, sequence, candidate.checkpoint());
                    updates.insertOutbox(document, epoch, sequence);
                    return new Saved(document, epoch, sequence, updateId, false);
                    });
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
        if (snapshot.role() == WorkspaceRole.VIEWER) throw new DocumentUpdateException("ACCESS_DENIED");
        if (snapshot.archived()) throw new DocumentUpdateException("DOCUMENT_ARCHIVED");
        if (snapshot.epoch() != epoch) throw new DocumentUpdateException("EPOCH_CHANGED");
    }
    private Saved verifyDuplicate(UUID document, long epoch, UUID id, UUID actor, byte[] payload, DocumentUpdateRepository.Existing existing) {
        if (!existing.actor().equals(actor) || !Arrays.equals(existing.payload(), payload)) throw new DocumentUpdateException("UPDATE_ID_CONFLICT");
        return new Saved(document, epoch, existing.sequence(), id, true);
    }
}
