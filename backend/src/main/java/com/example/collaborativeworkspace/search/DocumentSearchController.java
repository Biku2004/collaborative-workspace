package com.example.collaborativeworkspace.search;

import com.example.collaborativeworkspace.auth.UserRepository;
import com.example.collaborativeworkspace.collaboration.transport.SocketSessionRegistry;
import com.example.collaborativeworkspace.workspaces.WorkspaceResourceNotFoundException;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("dev")
@RequestMapping("/api/workspaces/{workspaceId}/search")
public class DocumentSearchController {
    private final DocumentSearchService search;
    private final UserRepository users;
    private final SocketSessionRegistry sessions;
    public DocumentSearchController(DocumentSearchService search, UserRepository users, SocketSessionRegistry sessions) { this.search = search; this.users = users; this.sessions = sessions; }
    private UUID actor(Authentication auth) { return users.findIdByEmail(auth.getName()).orElseThrow(WorkspaceResourceNotFoundException::new); }
    @GetMapping
    public ResponseEntity<DocumentSearchService.Results> search(@PathVariable UUID workspaceId, @RequestParam String q,
            @RequestParam(defaultValue = "20") int limit, Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(search.search(workspaceId, actor(auth), q, limit));
    }
    @PostMapping("/rebuild")
    public ResponseEntity<Map<String, Integer>> rebuild(@PathVariable UUID workspaceId, Authentication auth, HttpServletRequest http) {
        var session = http.getSession(false);
        int cleared = search.rebuild(workspaceId, actor(auth), sessions.publicationGuard(session), () -> {
            try { session.getCreationTime(); return true; } catch (IllegalStateException ex) { return false; }
        });
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Map.of("clearedDocuments", cleared));
    }
}
