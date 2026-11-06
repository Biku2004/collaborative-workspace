package com.example.collaborativeworkspace;

import java.util.UUID;

/** In-process invalidation hint. Current database membership remains authoritative. */
public record WorkspaceAccessReduced(UUID workspaceId, UUID userId) {}
