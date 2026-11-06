package com.example.collaborativeworkspace.workspaces;

import java.time.Instant;
import java.util.UUID;

public record Workspace(UUID id, String name, Instant createdAt, String role) {}
