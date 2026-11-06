package com.example.collaborativeworkspace.workspaces;

import com.example.collaborativeworkspace.common.pagination.PageSlice;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("dev")
@RequestMapping("/api/workspaces/{workspaceId}/members")
public class WorkspaceMembershipController {
    private final WorkspaceMembershipService service;
    public WorkspaceMembershipController(WorkspaceMembershipService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<List<WorkspaceMember>> list(@PathVariable UUID workspaceId, Authentication authentication,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit,
            @org.springframework.web.bind.annotation.RequestParam(required = false) java.util.UUID after) {
        PageSlice<WorkspaceMember> page = service.list(workspaceId, authentication.getName(), limit, after);
        var response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (page.nextCursor() != null) response.header("X-Next-Cursor", page.nextCursor().toString());
        return response.body(page.items());
    }

    @PostMapping
    public ResponseEntity<WorkspaceMember> setRole(@PathVariable UUID workspaceId, @Valid @RequestBody ChangeWorkspaceMemberRequest request, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.setRole(workspaceId, authentication.getName(), request));
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> remove(@PathVariable UUID workspaceId, @PathVariable UUID userId, Authentication authentication) {
        service.remove(workspaceId, authentication.getName(), userId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
