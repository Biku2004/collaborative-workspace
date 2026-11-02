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
        if (key == null) return create.get();
        if (!key.matches("[A-Za-z0-9_-]{1,128}")) throw new InvalidUserInputException();
        jdbc.update("INSERT INTO creation_requests (user_id, operation, request_key, request_payload) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING", user, operation, key, payload);
        StoredRequest stored = jdbc.queryForObject("SELECT request_payload, response_json FROM creation_requests WHERE user_id = ? AND operation = ? AND request_key = ? FOR UPDATE",
                (rs, row) -> new StoredRequest(rs.getString("request_payload"), rs.getString("response_json")), user, operation, key);
        if (stored == null) throw new IllegalStateException("Creation request record is missing");
        if (!payload.equals(stored.payload())) throw new IdempotencyConflictException();
        if (stored.response() != null) return json.readValue(stored.response(), type);
        T result = create.get();
        jdbc.update("UPDATE creation_requests SET response_json = ? WHERE user_id = ? AND operation = ? AND request_key = ?", json.writeValueAsString(result), user, operation, key);
        return result;
    }

    private record StoredRequest(String payload, String response) {}
}
