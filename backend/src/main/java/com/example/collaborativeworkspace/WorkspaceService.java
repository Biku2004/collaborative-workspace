package com.example.collaborativeworkspace;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceService {
    private final WorkspaceRepository workspaces;
    private final UserRepository users;
    private final IdempotencyService idempotency;

    public WorkspaceService(WorkspaceRepository workspaces, UserRepository users, IdempotencyService idempotency) {
        this.workspaces = workspaces;
        this.users = users;
        this.idempotency = idempotency;
    }

    @Transactional
    public Workspace create(String authenticatedEmail, String name) {
        return create(authenticatedEmail, name, null);
    }

    @Transactional
    public Workspace create(String authenticatedEmail, String name, String requestKey) {
        UUID userId = userId(authenticatedEmail);
        return idempotency.execute(userId, "workspace", requestKey, name.strip(), Workspace.class, () -> {
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = workspaces.insertWorkspace(workspaceId, name.strip(), userId);
        workspaces.insertOwner(workspaceId, userId);
        return workspace;
        });
    }

    @Transactional(readOnly = true)
    public List<Workspace> list(String authenticatedEmail) {
        return list(authenticatedEmail, 20, null).items();
    }

    @Transactional(readOnly = true)
    public PageSlice<Workspace> list(String email, int limit, UUID after) {
        PageSlice.validateLimit(limit);
        return PageSlice.from(workspaces.findByMember(userId(email), limit + 1, after), limit, Workspace::id);
    }

    private UUID userId(String email) {
        return users.findIdByEmail(email).orElseThrow(() -> new AccessDeniedException("User is not available"));
    }
}
