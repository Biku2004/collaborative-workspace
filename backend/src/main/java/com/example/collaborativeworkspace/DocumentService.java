package com.example.collaborativeworkspace;

import java.util.UUID;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentService {
    private final DocumentRepository repository;
    private final WorkspaceRepository workspaces;
    private final UserRepository users;
    private final IdempotencyService idempotency;
    private final DocumentUpdateRepository updates;
    private final tools.jackson.databind.json.JsonMapper json;
    public DocumentService(DocumentRepository repository, WorkspaceRepository workspaces, UserRepository users, IdempotencyService idempotency,
            DocumentUpdateRepository updates, tools.jackson.databind.json.JsonMapper json) {
        this.repository = repository; this.workspaces = workspaces; this.users = users;
        this.idempotency = idempotency; this.updates = updates; this.json = json;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Document create(UUID workspaceId, String authenticatedEmail, String title) {
        return create(workspaceId, authenticatedEmail, title, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Document create(UUID workspaceId, String authenticatedEmail, String title, String requestKey) {
        return create(workspaceId, authenticatedEmail, title, requestKey, "PLAIN_TEXT_V1");
    }

    /** Format-aware creation; the HTTP boundary applies the feature gate. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Document create(UUID workspaceId, String authenticatedEmail, String title, String requestKey, String format) {
        if (!"PLAIN_TEXT_V1".equals(format) && !"RICH_TEXT_V1".equals(format)) throw new InvalidUserInputException();
        byte[] bootstrap = "RICH_TEXT_V1".equals(format) ? new RichTextContract(json).initialUpdate() : null;
        UUID userId = users.findIdByEmail(authenticatedEmail).orElseThrow(() -> new AccessDeniedException("User is not available"));
        if (!workspaces.lockWorkspace(workspaceId)) throw new WorkspaceResourceNotFoundException();
        WorkspaceRole role = workspaces.memberRole(workspaceId, userId).orElseThrow(WorkspaceResourceNotFoundException::new);
        if (role == WorkspaceRole.VIEWER) throw new AccessDeniedException("Editing permission required");
        return idempotency.executeDocument(userId, "document:" + workspaceId, requestKey, title.strip(), format, Document.class, () -> {
            Document document = repository.create(UUID.randomUUID(), workspaceId, title.strip(), format);
            if (bootstrap != null) {
                byte[] digest;
                try { digest = java.security.MessageDigest.getInstance("SHA-256").digest(bootstrap); }
                catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
                updates.ensureStream(document.id());
                updates.saveUpdate(document.id(), 0, 1, UUID.randomUUID(), userId, bootstrap, digest);
                updates.saveHead(document.id(), 0, 1, bootstrap);
                updates.insertOutbox(document.id(), 0, 1);
            }
            return document;
        });
    }

    @Transactional(readOnly = true)
    public Document get(UUID documentId, String authenticatedEmail) {
        UUID userId = users.findIdByEmail(authenticatedEmail).orElseThrow(DocumentNotFoundException::new);
        return repository.findVisible(documentId, userId).orElseThrow(DocumentNotFoundException::new);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Document rename(UUID workspaceId, UUID documentId, String authenticatedEmail, RenameDocumentRequest request) {
        requireDocumentEditor(workspaceId, documentId, authenticatedEmail);
        return repository.rename(workspaceId, documentId, request.version(), request.title().strip())
                .orElseThrow(DocumentConflictException::new);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Document archive(UUID workspaceId, UUID documentId, String authenticatedEmail, ArchiveDocumentRequest request) {
        requireDocumentEditor(workspaceId, documentId, authenticatedEmail);
        return repository.archive(workspaceId, documentId, request.version())
                .orElseThrow(DocumentConflictException::new);
    }

    private void requireDocumentEditor(UUID workspaceId, UUID documentId, String email) {
        UUID userId = users.findIdByEmail(email).orElseThrow(DocumentNotFoundException::new);
        if (!workspaces.lockWorkspace(workspaceId)) throw new DocumentNotFoundException();
        WorkspaceRole role = workspaces.memberRole(workspaceId, userId).orElseThrow(DocumentNotFoundException::new);
        if (!repository.existsInWorkspace(workspaceId, documentId)) throw new DocumentNotFoundException();
        if (role == WorkspaceRole.VIEWER) throw new AccessDeniedException("Editing permission required");
    }

    @Transactional(readOnly = true)
    public DocumentPage list(UUID workspaceId, String authenticatedEmail, int limit, String encodedCursor) {
        if (limit < 1 || limit > 100) throw new InvalidDocumentPageException();
        DocumentCursor cursor = DocumentCursor.parse(encodedCursor, workspaceId);
        UUID userId = users.findIdByEmail(authenticatedEmail).orElseThrow(WorkspaceResourceNotFoundException::new);
        List<Document> rows = repository.listVisible(workspaceId, userId, cursor, limit + 1);
        if (rows.isEmpty()) throw new WorkspaceResourceNotFoundException();
        List<Document> documents = rows.stream().filter(java.util.Objects::nonNull).toList();
        boolean hasMore = documents.size() > limit;
        List<Document> items = hasMore ? documents.subList(0, limit) : documents;
        String next = hasMore ? DocumentCursor.encode(workspaceId, items.get(items.size() - 1)) : null;
        return new DocumentPage(items, next);
    }
}
