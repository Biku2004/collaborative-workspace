package com.example.collaborativeworkspace.documents;

import java.time.Instant;
import java.util.UUID;

public record Document(UUID id, UUID workspaceId, String title, Instant createdAt, long version, boolean archived, String collaborationFormat) {
    public Document {
        // Stored idempotency responses created before V10 do not contain this field.
        if (collaborationFormat == null) collaborationFormat = "PLAIN_TEXT_V1";
    }
}
