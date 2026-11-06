package com.example.collaborativeworkspace.documents.history;

import com.example.collaborativeworkspace.auth.UserRepository;
import com.example.collaborativeworkspace.collaboration.transport.SocketSessionRegistry;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("dev")
@RequestMapping("/api/workspaces/{workspaceId}/documents/{documentId}")
public class DocumentRestoreController {
    private final DocumentRestoreService restores;
    private final UserRepository users;
    private final SocketSessionRegistry sessions;
    public DocumentRestoreController(DocumentRestoreService restores, UserRepository users, SocketSessionRegistry sessions) { this.restores = restores; this.users = users; this.sessions = sessions; }
    private UUID actor(Authentication authentication) { return users.findIdByEmail(authentication.getName()).orElseThrow(() -> new RevisionException("NOT_FOUND")); }
    @GetMapping("/baseline")
    public ResponseEntity<DocumentRestoreService.Baseline> baseline(@PathVariable UUID workspaceId, @PathVariable UUID documentId, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(restores.baseline(workspaceId, documentId, actor(authentication)));
    }
    @PostMapping("/restore")
    public ResponseEntity<DocumentRestoreRepository.Restored> restore(@PathVariable UUID workspaceId, @PathVariable UUID documentId,
            Authentication authentication, @Valid @RequestBody RestoreRevisionRequest request, HttpServletRequest http) {
        var session = http.getSession(false);
        var result = restores.restore(workspaceId, documentId, actor(authentication), request, sessions.publicationGuard(session), () -> {
            if (session == null) return false;
            try { session.getCreationTime(); return true; } catch (IllegalStateException ex) { return false; }
        });
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
