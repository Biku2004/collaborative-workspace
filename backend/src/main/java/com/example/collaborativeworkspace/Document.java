package com.example.collaborativeworkspace;

import java.time.Instant;
import java.util.UUID;

public record Document(UUID id, String title, Instant createdAt) {}
