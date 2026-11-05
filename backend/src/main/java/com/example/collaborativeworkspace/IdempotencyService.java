package com.example.collaborativeworkspace;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class IdempotencyService {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    public IdempotencyService(JdbcTemplate jdbc, JsonMapper json) { this.jdbc = jdbc; this.json = json; }

    // The request record and resource creation must commit or roll back together.
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T execute(UUID user, String operation, String key, String payload, Class<T> type, Supplier<T> create) {
        return execute(user, operation, key, payload, null, null, type, create);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T executeDocument(UUID user, String operation, String key, String payload, String format, Class<T> type, Supplier<T> create) {
        return execute(user, operation, key, payload, format, "PLAIN_TEXT_V1", type, create);
    }

    private <T> T execute(UUID user, String operation, String key, String payload, String variant, String legacyVariant, Class<T> type, Supplier<T> create) {
        if (key == null) return create.get();
        if (!key.matches("[A-Za-z0-9_-]{1,128}")) throw new InvalidUserInputException();
        jdbc.update("INSERT INTO creation_requests (user_id, operation, request_key, request_payload, request_variant) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING", user, operation, key, payload, variant);
        StoredRequest stored = jdbc.queryForObject("SELECT request_payload, response_json, request_variant FROM creation_requests WHERE user_id = ? AND operation = ? AND request_key = ? FOR UPDATE",
                (rs, row) -> new StoredRequest(rs.getString("request_payload"), rs.getString("response_json"), rs.getString("request_variant")), user, operation, key);
        if (stored == null) throw new IllegalStateException("Creation request record is missing");
        String storedVariant = stored.variant() == null ? legacyVariant : stored.variant();
        if (!payload.equals(stored.payload()) || !java.util.Objects.equals(variant, storedVariant)) throw new IdempotencyConflictException();
        if (stored.response() != null) return json.readValue(stored.response(), type);
        T result = create.get();
        jdbc.update("UPDATE creation_requests SET response_json = ? WHERE user_id = ? AND operation = ? AND request_key = ?", json.writeValueAsString(result), user, operation, key);
        return result;
    }

    private record StoredRequest(String payload, String response, String variant) {}
}
