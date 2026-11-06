package com.example.collaborativeworkspace.workspaces;

import java.util.UUID;

public record WorkspaceMember(UUID userId, String email, WorkspaceRole role) {}
