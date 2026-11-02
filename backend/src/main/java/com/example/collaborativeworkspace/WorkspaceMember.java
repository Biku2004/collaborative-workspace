package com.example.collaborativeworkspace;

import java.util.UUID;

public record WorkspaceMember(UUID userId, String email, WorkspaceRole role) {}
