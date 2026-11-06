package com.example.collaborativeworkspace;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconstructs a bounded committed prefix without holding locks during engine calls. */
@Service
@Profile("dev")
public class DocumentCheckpointService {
    static final int MAX_UPDATES_PER_JOB = 16;
    private final DocumentUpdateRepository updates;
    private final DocumentCheckpointRepository checkpoints;
    private final WorkspaceRepository workspaces;
    private final YjsEngineClient engine;
    private final CollaborationFormatPolicy formats;
    private final TransactionTemplate transaction;
    // Keep checkpoint work from occupying both private-engine worker slots.
    private final Semaphore jobs = new Semaphore(1);

    public record Published(DocumentCheckpointRepository.Checkpoint checkpoint, long observedHead) {}

    public DocumentCheckpointService(DocumentUpdateRepository updates, DocumentCheckpointRepository checkpoints,
            WorkspaceRepository workspaces, YjsEngineClient engine, CollaborationFormatPolicy formats, PlatformTransactionManager manager) {
        this.updates = updates; this.checkpoints = checkpoints; this.workspaces = workspaces; this.engine = engine; this.formats = formats;
        transaction = new TransactionTemplate(manager); transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Published create(UUID workspace, UUID document, UUID actor) {
        return create(workspace, document, actor, () -> true);
    }

    public Published create(UUID workspace, UUID document, UUID actor, BooleanSupplier sessionValid) {
        Objects.requireNonNull(workspace); Objects.requireNonNull(document); Objects.requireNonNull(actor);
        Objects.requireNonNull(sessionValid);
        if (!jobs.tryAcquire()) throw new CheckpointException("CHECKPOINT_BUSY");
        try {
            if (!sessionValid.getAsBoolean()) throw new CheckpointException("ACCESS_DENIED");
            var head = accessible(workspace, document, actor);
            requireAllowed(head, true);
            var previous = checkpoints.latest(document, head.epoch(), head.sequence());
            long start = 0;
            byte[] state = DocumentUpdateRepository.EMPTY_CHECKPOINT.clone();
            if (previous.isPresent()) {
                var checkpoint = previous.get();
                verify(checkpoint, head.collaborationFormat());
                start = checkpoint.sequence(); state = checkpoint.bytes();
            }
            long through = start + Math.min(head.sequence() - start, MAX_UPDATES_PER_JOB);
            var tail = checkpoints.tail(document, head.epoch(), start, through, MAX_UPDATES_PER_JOB);
            long sequence = start;
            for (var update : tail) {
                if (update.sequence() != sequence + 1 || !MessageDigest.isEqual(hash(update.bytes()), update.digest()))
                    throw new CheckpointException("CHECKPOINT_LOG_CORRUPT");
                if ("RICH_TEXT_V1".equals(head.collaborationFormat())) {
                    var candidate = sequence == 0 ? engine.reconstructRich(List.of(update.bytes()))
                            : engine.reconstructRichForCommit(List.of(state, update.bytes()));
                    if (!candidate.initialized()) throw new CheckpointException("CHECKPOINT_LOG_CORRUPT");
                    state = candidate.checkpoint();
                } else state = engine.reconstruct(List.of(state, update.bytes())).checkpoint();
                sequence = update.sequence();
            }
            if (sequence != through) throw new CheckpointException("CHECKPOINT_LOG_CORRUPT");
            // Replay the resulting candidate once more through the schema validator.
            byte[] validated;
            if ("RICH_TEXT_V1".equals(head.collaborationFormat())) {
                var candidate = engine.reconstructRich(List.of(state));
                if (!candidate.initialized()) throw new CheckpointException("CHECKPOINT_LOG_CORRUPT");
                validated = candidate.checkpoint();
            } else validated = engine.reconstruct(List.of(state)).checkpoint();
            if (!Arrays.equals(state, validated) || (through == head.sequence() && !Arrays.equals(state, head.checkpoint())))
                throw new CheckpointException("CHECKPOINT_VERIFICATION_FAILED");
            var proposed = new DocumentCheckpointRepository.Checkpoint(document, head.epoch(), through, head.collaborationFormat(), state, hash(state));
            var result = transaction.execute(status -> {
                if (!workspaces.lockWorkspace(workspace)) throw new CheckpointException("NOT_FOUND");
                if (!updates.lockDocument(workspace, document)) throw new CheckpointException("NOT_FOUND");
                if (!sessionValid.getAsBoolean()) throw new CheckpointException("ACCESS_DENIED");
                var current = accessible(workspace, document, actor);
                requireAllowed(current, true);
                if (current.epoch() != head.epoch()) throw new CheckpointException("EPOCH_CHANGED");
                if (!current.collaborationFormat().equals(head.collaborationFormat()) || current.sequence() < through)
                    throw new CheckpointException("CHECKPOINT_VERIFICATION_FAILED");
                updates.ensureStream(document);
                if (!checkpoints.insert(proposed)) {
                    var existing = checkpoints.latest(document, head.epoch(), through).orElseThrow(() -> new CheckpointException("CHECKPOINT_VERIFICATION_FAILED"));
                    verify(existing, head.collaborationFormat());
                    if (existing.sequence() != through || !Arrays.equals(existing.bytes(), proposed.bytes()))
                        throw new CheckpointException("CHECKPOINT_VERIFICATION_FAILED");
                    return existing;
                }
                return proposed;
            });
            return new Published(Objects.requireNonNull(result), head.sequence());
        } finally { jobs.release(); }
    }

    public DocumentCheckpointRepository.Checkpoint latest(UUID workspace, UUID document, UUID actor) {
        var head = accessible(workspace, document, actor);
        requireAllowed(head, false);
        var checkpoint = checkpoints.latest(document, head.epoch(), head.sequence()).orElseThrow(() -> new CheckpointException("NOT_FOUND"));
        verify(checkpoint, head.collaborationFormat());
        return checkpoint;
    }

    private DocumentUpdateRepository.Snapshot accessible(UUID workspace, UUID document, UUID actor) {
        return updates.findAccessible(workspace, document, actor).orElseThrow(() -> new CheckpointException("NOT_FOUND"));
    }
    private void requireAllowed(DocumentUpdateRepository.Snapshot head, boolean write) {
        if (!formats.supports(head.collaborationFormat())) throw new CheckpointException("SCHEMA_UNSUPPORTED");
        if (head.archived()) throw new CheckpointException("NOT_FOUND");
        if (write && head.role() == WorkspaceRole.VIEWER) throw new CheckpointException("ACCESS_DENIED");
    }
    private void verify(DocumentCheckpointRepository.Checkpoint checkpoint, String format) {
        if (!format.equals(checkpoint.format()) || !MessageDigest.isEqual(hash(checkpoint.bytes()), checkpoint.digest()))
            throw new CheckpointException("CHECKPOINT_CORRUPT");
    }
    private static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
}
