package com.example.collaborativeworkspace;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Base64;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("dev")
@RequestMapping("/api/workspaces/{workspaceId}/documents/{documentId}/checkpoints")
public class DocumentCheckpointController {
    private final DocumentCheckpointService checkpoints;
    private final UserRepository users;
    private final SocketSessionRegistry sessions;
    public DocumentCheckpointController(DocumentCheckpointService checkpoints, UserRepository users, SocketSessionRegistry sessions) { this.checkpoints = checkpoints; this.users = users; this.sessions = sessions; }

    public record Created(long epoch, long throughSequence, long observedHead, String format, String sha256) {}
    public record View(long epoch, long throughSequence, String format, String checkpoint, String sha256) {}

    @PostMapping
    public ResponseEntity<Created> create(@PathVariable UUID workspaceId, @PathVariable UUID documentId,
            Authentication authentication, HttpServletRequest request) {
        UUID actor = users.findIdByEmail(authentication.getName()).orElseThrow(() -> new CheckpointException("NOT_FOUND"));
        HttpSession session = request.getSession(false);
        var result = checkpoints.create(workspaceId, documentId, actor, sessions.publicationGuard(session), () -> valid(session));
        var checkpoint = result.checkpoint();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(new Created(checkpoint.epoch(), checkpoint.sequence(),
                result.observedHead(), checkpoint.format(), Base64.getEncoder().encodeToString(checkpoint.digest())));
    }

    @GetMapping("/latest")
    public ResponseEntity<View> latest(@PathVariable UUID workspaceId, @PathVariable UUID documentId, Authentication authentication) {
        UUID actor = users.findIdByEmail(authentication.getName()).orElseThrow(() -> new CheckpointException("NOT_FOUND"));
        var checkpoint = checkpoints.latest(workspaceId, documentId, actor);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new View(checkpoint.epoch(), checkpoint.sequence(), checkpoint.format(),
                Base64.getEncoder().encodeToString(checkpoint.bytes()), Base64.getEncoder().encodeToString(checkpoint.digest())));
    }

    private static boolean valid(HttpSession session) {
        if (session == null) return false;
        try { session.getCreationTime(); return true; }
        catch (IllegalStateException ex) { return false; }
    }
}
