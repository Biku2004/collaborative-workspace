package com.example.collaborativeworkspace.documents;

import com.example.collaborativeworkspace.common.pagination.InvalidDocumentPageException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public record DocumentCursor(Instant createdAt, UUID id) {
    public static DocumentCursor parse(String encoded, UUID workspaceId) {
        if (encoded == null) return null;
        if (encoded.isBlank() || encoded.length() > 200) throw new InvalidDocumentPageException();
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 3 || !workspaceId.toString().equals(parts[0])) throw new InvalidDocumentPageException();
            Instant time = Instant.parse(parts[1]);
            if (time.isBefore(Instant.parse("0001-01-01T00:00:00Z")) || !time.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))) throw new InvalidDocumentPageException();
            UUID id = UUID.fromString(parts[2]);
            if (!id.toString().equals(parts[2])) throw new InvalidDocumentPageException();
            return new DocumentCursor(time, id);
        } catch (IllegalArgumentException | java.time.DateTimeException ex) {
            throw new InvalidDocumentPageException();
        }
    }

    public static String encode(UUID workspaceId, Document document) {
        return encode(workspaceId, document.createdAt(), document.id());
    }

    public static String encode(UUID scopeId, Instant createdAt, UUID id) {
        String value = scopeId + "|" + createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
