package com.example.collaborativeworkspace;

import java.time.Instant;
import java.util.UUID;

public record Document(UUID id, UUID workspaceId, String title, Instant createdAt, long version, boolean archived) {}
