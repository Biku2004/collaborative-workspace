package com.example.collaborativeworkspace.documents;

import com.example.collaborativeworkspace.collaboration.engine.CollaborationFormatPolicy;
import com.example.collaborativeworkspace.common.api.InvalidUserInputException;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("dev")
@RequestMapping("/api/workspaces/{workspaceId}/documents")
public class WorkspaceDocumentController {
    private final DocumentService service;
    private final CollaborationFormatPolicy formats;
    public WorkspaceDocumentController(DocumentService service, CollaborationFormatPolicy formats) { this.service = service; this.formats = formats; }

    @PostMapping
    public ResponseEntity<Document> create(@PathVariable UUID workspaceId, @Valid @RequestBody CreateDocumentRequest request, Authentication authentication,
            @org.springframework.web.bind.annotation.RequestHeader(name = "Idempotency-Key", required = false) String requestKey) {
        String format = request.format() == null ? "PLAIN_TEXT_V1" : request.format();
        if (!formats.supports(format)) throw new InvalidUserInputException();
        Document document = service.create(workspaceId, authentication.getName(), request.title(), requestKey, format);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(document);
    }

    @GetMapping
    public ResponseEntity<DocumentPage> list(@PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(workspaceId, authentication.getName(), limit, cursor));
    }

    @PatchMapping("/{documentId}")
    public ResponseEntity<Document> rename(@PathVariable UUID workspaceId, @PathVariable UUID documentId,
            @Valid @RequestBody RenameDocumentRequest request, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.rename(workspaceId, documentId, authentication.getName(), request));
    }

    @PostMapping("/{documentId}/archive")
    public ResponseEntity<Document> archive(@PathVariable UUID workspaceId, @PathVariable UUID documentId,
            @Valid @RequestBody ArchiveDocumentRequest request, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.archive(workspaceId, documentId, authentication.getName(), request));
    }
}
