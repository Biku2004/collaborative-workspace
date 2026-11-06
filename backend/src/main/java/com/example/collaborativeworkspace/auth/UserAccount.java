package com.example.collaborativeworkspace.auth;

import java.time.Instant;
import java.util.UUID;

public record UserAccount(UUID id, String email, Instant createdAt) {}
