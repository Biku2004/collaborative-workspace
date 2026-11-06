package com.example.collaborativeworkspace.documents.history;

import com.example.collaborativeworkspace.collaboration.engine.CollaborationFormatPolicy;
import com.example.collaborativeworkspace.collaboration.engine.YjsEngineClient;
import com.example.collaborativeworkspace.collaboration.engine.YjsEngineException;
import com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateRepository;
import com.example.collaborativeworkspace.common.api.InvalidUserInputException;
import com.example.collaborativeworkspace.common.pagination.InvalidDocumentPageException;
import com.example.collaborativeworkspace.documents.DocumentCursor;
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

@Service
@Profile("dev")
public class DocumentRevisionService {
    public record Page(List<DocumentRevisionRepository.Summary> items, String nextCursor) {}
    private final DocumentUpdateRepository updates;
    private final DocumentRevisionRepository revisions;
    private final WorkspaceRepository workspaces;
    private final CollaborationFormatPolicy formats;
    private final YjsEngineClient engine;
    private final TransactionTemplate transaction;
    private final DocumentSnapshotBudget jobs;
    public DocumentRevisionService(DocumentUpdateRepository updates, DocumentRevisionRepository revisions, WorkspaceRepository workspaces,
            CollaborationFormatPolicy formats, YjsEngineClient engine, PlatformTransactionManager manager, DocumentSnapshotBudget jobs) {
        this.updates = updates; this.revisions = revisions; this.workspaces = workspaces; this.formats = formats; this.engine = engine;
        this.jobs = jobs;
        transaction = new TransactionTemplate(manager); transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public DocumentRevisionRepository.Summary create(UUID workspace, UUID document, UUID actor, CreateRevisionRequest request, BooleanSupplier sessionValid) {
        return create(workspace, document, actor, request, sessionValid, sessionValid);
    }
    public DocumentRevisionRepository.Summary create(UUID workspace, UUID document, UUID actor, CreateRevisionRequest request, Object sessionGuard, BooleanSupplier sessionValid) {
        Objects.requireNonNull(request); Objects.requireNonNull(sessionValid);
        Objects.requireNonNull(sessionGuard);
        String name = request.name() == null ? "" : request.name().strip();
        if (name.isBlank() || name.length() > 100 || name.chars().anyMatch(Character::isISOControl) || request.requestId() == null) throw new InvalidUserInputException();
        var captured = accessible(workspace, document, actor, true);
        if (!sessionValid.getAsBoolean()) throw new RevisionException("ACCESS_DENIED");
        var retry = revisions.retry(document, actor, request.requestId());
        if (retry.isPresent()) return retry(retry.get(), name);
        if (!jobs.acquire()) throw new RevisionException("REVISION_BUSY");
        try {
            // A single SQL query captured this full state and sequence together before validation.
            byte[] state = captured.checkpoint(), validated;
            if ("RICH_TEXT_V1".equals(captured.collaborationFormat())) {
                var candidate = engine.reconstructRich(List.of(state));
                if (!candidate.initialized()) throw new RevisionException("REVISION_INVALID");
                validated = candidate.checkpoint();
            } else validated = engine.reconstruct(List.of(state)).checkpoint();
            if (!Arrays.equals(state, validated)) throw new RevisionException("REVISION_INVALID");
            byte[] digest = hash(state);
            synchronized (sessionGuard) {
                return Objects.requireNonNull(transaction.execute(status -> {
                    if (!workspaces.lockWorkspace(workspace) || !updates.lockDocument(workspace, document)) throw new RevisionException("NOT_FOUND");
                    if (!sessionValid.getAsBoolean()) throw new RevisionException("ACCESS_DENIED");
                    var current = accessible(workspace, document, actor, true);
                    var duplicate = revisions.retry(document, actor, request.requestId());
                    if (duplicate.isPresent()) return retry(duplicate.get(), name);
                    if (current.epoch() != captured.epoch()) throw new RevisionException("EPOCH_CHANGED");
                    if (!current.collaborationFormat().equals(captured.collaborationFormat())) throw new RevisionException("SCHEMA_UNSUPPORTED");
                    return revisions.insert(document, actor, request.requestId(), name, captured.epoch(), captured.sequence(), captured.collaborationFormat(), state, digest);
                }));
            }
        } catch (YjsEngineException ex) {
            if (ex.rejected()) throw new RevisionException("REVISION_INVALID");
            throw ex;
        } finally { jobs.release(); }
    }
    public Page list(UUID workspace, UUID document, UUID actor, int limit, String encodedCursor) {
        accessible(workspace, document, actor, false);
        if (limit < 1 || limit > 100) throw new InvalidDocumentPageException();
        var rows = revisions.list(document, DocumentCursor.parse(encodedCursor, document), limit + 1);
        boolean more = rows.size() > limit;
        var items = List.copyOf(more ? rows.subList(0, limit) : rows);
        var last = items.isEmpty() ? null : items.get(items.size() - 1);
        return new Page(items, more ? DocumentCursor.encode(document, last.createdAt(), last.id()) : null);
    }
    public DocumentRevisionRepository.Revision preview(UUID workspace, UUID document, UUID actor, UUID id) {
        accessible(workspace, document, actor, false);
        var revision = revisions.find(document, id).orElseThrow(() -> new RevisionException("NOT_FOUND"));
        if (!formats.supports(revision.summary().format())) throw new RevisionException("SCHEMA_UNSUPPORTED");
        verify(revision); return revision;
    }
    private DocumentUpdateRepository.Snapshot accessible(UUID workspace, UUID document, UUID actor, boolean write) {
        var snapshot = updates.findAccessible(workspace, document, actor).orElseThrow(() -> new RevisionException("NOT_FOUND"));
        if (snapshot.archived()) throw new RevisionException("NOT_FOUND");
        if (!formats.supports(snapshot.collaborationFormat())) throw new RevisionException("SCHEMA_UNSUPPORTED");
        if (write && snapshot.role() == WorkspaceRole.VIEWER) throw new RevisionException("ACCESS_DENIED");
        return snapshot;
    }
    private DocumentRevisionRepository.Summary retry(DocumentRevisionRepository.Revision revision, String name) {
        if (!revision.summary().name().equals(name)) throw new RevisionException("REVISION_ID_CONFLICT");
        verify(revision); return revision.summary();
    }
    private void verify(DocumentRevisionRepository.Revision revision) {
        if (!MessageDigest.isEqual(hash(revision.bytes()), revision.digest())) throw new RevisionException("REVISION_INVALID");
    }
    private static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
}
