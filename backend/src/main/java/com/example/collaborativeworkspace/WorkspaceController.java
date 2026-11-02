package com.example.collaborativeworkspace;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("dev")
@RequestMapping("/api/workspaces")
public class WorkspaceController {
    private final WorkspaceService service;
    public WorkspaceController(WorkspaceService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<Workspace> create(@Valid @RequestBody CreateWorkspaceRequest request, Authentication authentication,
            @org.springframework.web.bind.annotation.RequestHeader(name = "Idempotency-Key", required = false) String requestKey) {
        Workspace workspace = service.create(authentication.getName(), request.name(), requestKey);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(workspace);
    }

    @GetMapping
    public ResponseEntity<List<Workspace>> list(Authentication authentication,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit,
            @org.springframework.web.bind.annotation.RequestParam(required = false) java.util.UUID after) {
        PageSlice<Workspace> page = service.list(authentication.getName(), limit, after);
        var response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (page.nextCursor() != null) response.header("X-Next-Cursor", page.nextCursor().toString());
        return response.body(page.items());
    }

}
