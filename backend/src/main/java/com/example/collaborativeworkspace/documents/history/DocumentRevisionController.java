package com.example.collaborativeworkspace.documents.history;

import com.example.collaborativeworkspace.auth.UserRepository;
import com.example.collaborativeworkspace.collaboration.transport.SocketSessionRegistry;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Base64;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("dev")
@RequestMapping("/api/workspaces/{workspaceId}/documents/{documentId}/revisions")
public class DocumentRevisionController {
    public record Preview(DocumentRevisionRepository.Summary revision, String checkpoint, String sha256) {}
    private final DocumentRevisionService revisions;
    private final UserRepository users;
    private final SocketSessionRegistry sessions;
    public DocumentRevisionController(DocumentRevisionService revisions, UserRepository users, SocketSessionRegistry sessions) { this.revisions = revisions; this.users = users; this.sessions = sessions; }
    private UUID actor(Authentication authentication) { return users.findIdByEmail(authentication.getName()).orElseThrow(() -> new RevisionException("NOT_FOUND")); }
    @PostMapping
    public ResponseEntity<DocumentRevisionRepository.Summary> create(@PathVariable UUID workspaceId, @PathVariable UUID documentId,
            @Valid @RequestBody CreateRevisionRequest request, Authentication authentication, HttpServletRequest http) {
        var session = http.getSession(false);
        var result = revisions.create(workspaceId, documentId, actor(authentication), request, sessions.publicationGuard(session), () -> {
            if (session == null) return false;
            try { session.getCreationTime(); return true; } catch (IllegalStateException ex) { return false; }
        });
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(result);
    }
    @GetMapping
    public ResponseEntity<DocumentRevisionService.Page> list(@PathVariable UUID workspaceId, @PathVariable UUID documentId, Authentication authentication,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(revisions.list(workspaceId, documentId, actor(authentication), limit, cursor));
    }
    @GetMapping("/{revisionId}")
    public ResponseEntity<Preview> preview(@PathVariable UUID workspaceId, @PathVariable UUID documentId, @PathVariable UUID revisionId, Authentication authentication) {
        var result = revisions.preview(workspaceId, documentId, actor(authentication), revisionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Preview(result.summary(), Base64.getEncoder().encodeToString(result.bytes()), Base64.getEncoder().encodeToString(result.digest())));
    }
}
