package com.example.collaborativeworkspace.collaboration.engine;

import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Bounded plain-text extraction from the private engine's already-validated rich JSON. Never interprets HTML. */
final class RichSearchText {
    private static final int MAX_DEPTH = 32, MAX_NODES = 16384, MAX_UNITS = 32768;
    private static final Set<String> CONTAINERS = Set.of("doc", "paragraph", "heading", "bulletList", "orderedList", "listItem");
    private final StringBuilder text = new StringBuilder();
    private int nodes;
    static String extract(JsonNode document) {
        var visitor = new RichSearchText(); visitor.visit(document, 0);
        if (!visitor.text.isEmpty() && visitor.text.charAt(visitor.text.length() - 1) == '\n') visitor.text.setLength(visitor.text.length() - 1);
        return visitor.text.toString();
    }
    private void visit(JsonNode node, int depth) {
        if (node == null || !node.isObject() || !node.path("type").isTextual() || ++nodes > MAX_NODES || depth > MAX_DEPTH) throw new YjsEngineException(false);
        String type = node.path("type").asText();
        if ("text".equals(type)) {
            if (!node.path("text").isTextual() || node.has("content")) throw new YjsEngineException(false);
            append(node.path("text").asText()); return;
        }
        if (!CONTAINERS.contains(type) || node.has("text")) throw new YjsEngineException(false);
        JsonNode content = node.path("content");
        if (!content.isMissingNode()) {
            if (!content.isArray()) throw new YjsEngineException(false);
            for (JsonNode child : content) visit(child, depth + 1);
        }
        if ("paragraph".equals(type) || "heading".equals(type)) append("\n");
    }
    private void append(String value) {
        if (text.length() + value.length() > MAX_UNITS) throw new YjsEngineException(false);
        text.append(value);
    }
}
