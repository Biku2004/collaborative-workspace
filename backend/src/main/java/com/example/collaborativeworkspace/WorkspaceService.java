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

    public WorkspaceService(WorkspaceRepository workspaces, UserRepository users) {
        this.workspaces = workspaces;
        this.users = users;
    }

    @Transactional
    public Workspace create(String authenticatedEmail, String name) {
        UUID userId = userId(authenticatedEmail);
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = workspaces.insertWorkspace(workspaceId, name.strip(), userId);
        workspaces.insertOwner(workspaceId, userId);
        return workspace;
    }

    @Transactional(readOnly = true)
    public List<Workspace> list(String authenticatedEmail) {
        return workspaces.findByMember(userId(authenticatedEmail));
    }

    private UUID userId(String email) {
        return users.findIdByEmail(email).orElseThrow(() -> new AccessDeniedException("User is not available"));
    }
}
