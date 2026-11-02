package com.example.collaborativeworkspace;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Local-only speculative validation client. No result from here is a committed edit. */
@Component
@Profile("dev")
public class YjsEngineClient {
    private static final int MAX_UPDATE_BYTES = 8192, MAX_TOTAL_BYTES = 32768, MAX_UPDATES = 16, MAX_RESPONSE_BYTES = 65536;
    private final URI endpoint;
    private final HttpClient client;
    private final JsonMapper json;
    private final Duration timeout;

    public record Candidate(String text, byte[] checkpoint) {
        public Candidate { checkpoint = checkpoint.clone(); }
        @Override public byte[] checkpoint() { return checkpoint.clone(); }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public YjsEngineClient(@Value("${workspace.yjs-engine-url:http://127.0.0.1:8091}") String base, JsonMapper json) {
        this(base, json, Duration.ofSeconds(4));
    }

    YjsEngineClient(String base, JsonMapper json, Duration timeout) {
        URI origin = URI.create(base);
        if (!"http".equals(origin.getScheme()) || !"127.0.0.1".equals(origin.getHost()) || origin.getPort() < 1 ||
                origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null ||
                (origin.getPath() != null && !origin.getPath().isEmpty())) throw new IllegalArgumentException("Engine URL must be a loopback HTTP origin with an explicit port");
        this.endpoint = origin.resolve("/v1/reconstruct"); this.json = json; this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Candidate reconstruct(List<byte[]> updates) {
        if (updates == null || updates.size() > MAX_UPDATES) throw new IllegalArgumentException("Invalid update count");
        int total = 0;
        for (byte[] update : updates) {
            if (update == null || update.length == 0 || update.length > MAX_UPDATE_BYTES) throw new IllegalArgumentException("Invalid update size");
            total += update.length;
        }
        if (total > MAX_TOTAL_BYTES) throw new IllegalArgumentException("Updates are too large");
        var encoded = updates.stream().map(Base64.getEncoder()::encodeToString).toList();
        var request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("version", 1, "updates", encoded)))).build();
        try {
            var response = client.send(request, ignored -> new BoundedBodySubscriber(MAX_RESPONSE_BYTES));
            if (response.statusCode() == 400 || response.statusCode() == 413 || response.statusCode() == 422) throw new YjsEngineException(true);
            if (response.statusCode() != 200) throw new YjsEngineException(false);
            var body = json.readTree(response.body());
            if (body == null || !body.isObject() || !body.path("version").isIntegralNumber() || !body.path("version").canConvertToInt() || body.path("version").intValue() != 1 ||
                    !body.path("status").isTextual() || !"candidate".equals(body.path("status").asText()) ||
                    !body.path("text").isTextual() || body.path("text").asText().length() > 16384 || !body.path("checkpoint").isTextual()) {
                throw new YjsEngineException(false);
            }
            String encodedCheckpoint = body.path("checkpoint").asText();
            byte[] checkpoint = Base64.getDecoder().decode(encodedCheckpoint);
            if (checkpoint.length == 0 || checkpoint.length > MAX_UPDATE_BYTES || !Base64.getEncoder().encodeToString(checkpoint).equals(encodedCheckpoint)) throw new YjsEngineException(false);
            return new Candidate(body.path("text").asText(), checkpoint);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new YjsEngineException(false);
        } catch (java.io.IOException | IllegalArgumentException | tools.jackson.core.JacksonException ex) {
            throw new YjsEngineException(false);
        }
    }
}
