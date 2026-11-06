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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Local-only speculative validation client. No result from here is a committed edit. */
@Component
@Profile("dev")
public class YjsEngineClient {
    private static final int MAX_UPDATE_BYTES = 8192, MAX_RICH_CHECKPOINT_BYTES = 65536, MAX_TOTAL_BYTES = 32768, MAX_UPDATES = 16,
            MAX_RESPONSE_BYTES = 65536, MAX_RICH_RESPONSE_BYTES = 262144;
    private final URI endpoint;
    private final URI richEndpoint;
    private final URI freshEndpoint;
    private final HttpClient client;
    private final JsonMapper json;
    private final Duration timeout;

    public record Candidate(String text, byte[] checkpoint) {
        public Candidate { checkpoint = checkpoint.clone(); }
        @Override public byte[] checkpoint() { return checkpoint.clone(); }
    }
    public record RichCandidate(boolean initialized, byte[] checkpoint) {
        public RichCandidate { checkpoint = checkpoint.clone(); }
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
        this.endpoint = origin.resolve("/v1/reconstruct"); this.richEndpoint = origin.resolve("/v2/reconstruct");
        this.freshEndpoint = origin.resolve("/v3/fresh-state"); this.json = json; this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Candidate reconstruct(List<byte[]> updates) {
        JsonNode body = request(updates, false, false);
        if (body == null || !body.isObject() || !body.path("version").isIntegralNumber() || !body.path("version").canConvertToInt() || body.path("version").intValue() != 1 ||
                !body.path("status").isTextual() || !"candidate".equals(body.path("status").asText()) ||
                !body.path("text").isTextual() || body.path("text").asText().length() > 16384) throw new YjsEngineException(false);
        return new Candidate(body.path("text").asText(), checkpoint(body, MAX_UPDATE_BYTES));
    }

    /** Materialized content with new identities. Only the database service may commit an epoch transition. */
    public byte[] freshState(String format, byte[] source) {
        int maximum = switch (format) {
            case "PLAIN_TEXT_V1" -> MAX_UPDATE_BYTES;
            case "RICH_TEXT_V1" -> MAX_RICH_CHECKPOINT_BYTES;
            default -> throw new IllegalArgumentException("Unsupported format");
        };
        if (source == null || source.length == 0 || source.length > maximum) throw new IllegalArgumentException("Invalid checkpoint size");
        var body = exchange(freshEndpoint, Map.of("version", 3, "format", format, "checkpoint", Base64.getEncoder().encodeToString(source)), MAX_RICH_RESPONSE_BYTES);
        if (body == null || !body.isObject() || !body.path("version").isIntegralNumber() || !body.path("version").canConvertToInt() || body.path("version").intValue() != 3 ||
                !body.path("status").isTextual() || !"candidate".equals(body.path("status").asText()) ||
                !body.path("format").isTextual() || !format.equals(body.path("format").asText())) throw new YjsEngineException(false);
        return checkpoint(body, maximum);
    }

    public RichCandidate reconstructRich(List<byte[]> updates) {
        return reconstructRich(updates, false);
    }

    public RichCandidate reconstructRichForCommit(List<byte[]> updates) {
        if (updates == null || updates.size() != 2) throw new IllegalArgumentException("Commit requires checkpoint and update");
        RichCandidate candidate = reconstructRich(updates, true);
        if (!candidate.initialized()) throw new YjsEngineException(true);
        return candidate;
    }

    /** Read-only materialization for derived projections. This never accepts or commits an update. */
    public Candidate materialize(String format, byte[] source) {
        if ("PLAIN_TEXT_V1".equals(format)) return reconstruct(List.of(source));
        if (!"RICH_TEXT_V1".equals(format)) throw new IllegalArgumentException("Unsupported format");
        JsonNode body = request(List.of(source), true, false);
        var candidate = richCandidate(body);
        if (!candidate.initialized()) throw new YjsEngineException(true);
        return new Candidate(RichSearchText.extract(body.path("document")), candidate.checkpoint());
    }

    private RichCandidate reconstructRich(List<byte[]> updates, boolean commit) {
        JsonNode body = request(updates, true, commit);
        return richCandidate(body);
    }

    private RichCandidate richCandidate(JsonNode body) {
        if (body == null || !body.isObject() || !body.path("version").isIntegralNumber() || !body.path("version").canConvertToInt() || body.path("version").intValue() != 2 ||
                !body.path("status").isTextual() || !"candidate".equals(body.path("status").asText()) ||
                !body.path("format").isTextual() || !"RICH_TEXT_V1".equals(body.path("format").asText()) ||
                !body.path("initialized").isBoolean()) throw new YjsEngineException(false);
        boolean initialized = body.path("initialized").booleanValue();
        JsonNode document = body.path("document");
        if (initialized ? (!document.isObject() || !document.path("type").isTextual() || !"doc".equals(document.path("type").asText()) ||
                !document.path("content").isArray() || document.path("content").isEmpty()) : !document.isNull()) throw new YjsEngineException(false);
        return new RichCandidate(initialized, checkpoint(body, MAX_RICH_CHECKPOINT_BYTES));
    }

    private byte[] checkpoint(JsonNode body, int maximum) {
        if (!body.path("checkpoint").isTextual()) throw new YjsEngineException(false);
        String encoded = body.path("checkpoint").asText();
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length == 0 || bytes.length > maximum || !Base64.getEncoder().encodeToString(bytes).equals(encoded)) throw new YjsEngineException(false);
            return bytes;
        } catch (IllegalArgumentException ex) { throw new YjsEngineException(false); }
    }

    private JsonNode request(List<byte[]> updates, boolean rich, boolean commit) {
        if (updates == null || updates.size() > MAX_UPDATES) throw new IllegalArgumentException("Invalid update count");
        int total = 0;
        for (int index = 0; index < updates.size(); index++) {
            byte[] update = updates.get(index);
            int maximum = rich && index == 0 ? MAX_RICH_CHECKPOINT_BYTES : MAX_UPDATE_BYTES;
            if (update == null || update.length == 0 || update.length > maximum) throw new IllegalArgumentException("Invalid update size");
            total += update.length;
        }
        if (total > (rich ? MAX_RICH_CHECKPOINT_BYTES + MAX_UPDATE_BYTES : MAX_TOTAL_BYTES)) throw new IllegalArgumentException("Updates are too large");
        var encoded = updates.stream().map(Base64.getEncoder()::encodeToString).toList();
        var payload = commit ? Map.of("version", 2, "format", "RICH_TEXT_V1", "mode", "commit", "updates", encoded)
                : rich ? Map.of("version", 2, "format", "RICH_TEXT_V1", "updates", encoded) : Map.of("version", 1, "updates", encoded);
        return exchange(rich ? richEndpoint : endpoint, payload, rich ? MAX_RICH_RESPONSE_BYTES : MAX_RESPONSE_BYTES);
    }

    private JsonNode exchange(URI uri, Map<String, ?> payload, int maximumResponse) {
        var request = HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
        try {
            var response = client.send(request, ignored -> new BoundedBodySubscriber(maximumResponse));
            if (response.statusCode() == 413) throw new YjsEngineException(true, true);
            if (response.statusCode() == 400 || response.statusCode() == 422) {
                boolean limit = false;
                try {
                    JsonNode error = json.readTree(response.body());
                    limit = error != null && error.isObject() && error.path("code").isTextual() && "TOO_LARGE".equals(error.path("code").asText());
                }
                catch (tools.jackson.core.JacksonException ignored) { /* A malformed rejection remains a rejection. */ }
                throw new YjsEngineException(true, limit);
            }
            if (response.statusCode() != 200) throw new YjsEngineException(false);
            return json.readTree(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new YjsEngineException(false);
        } catch (java.io.IOException | IllegalArgumentException | tools.jackson.core.JacksonException ex) {
            throw new YjsEngineException(false);
        }
    }
}
