package com.example.collaborativeworkspace.collaboration.engine;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RichSearchTextTest {
    private final JsonMapper json = JsonMapper.builder().build();
    @Test void extractsMarksHeadingsAndBothListTypesAsLiteralText() {
        var document = json.readTree("""
            {"type":"doc","content":[
              {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Hello","marks":[{"type":"bold"}]},{"type":"text","text":" world","marks":[{"type":"italic"}]}]},
              {"type":"bulletList","content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Bullet"}]}]}]},
              {"type":"orderedList","attrs":{"start":3},"content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"<script>literal</script>"}]}]}]}
            ]}
            """);
        assertEquals("Hello world\nBullet\n<script>literal</script>", RichSearchText.extract(document));
        assertEquals("", RichSearchText.extract(json.readTree("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]}")));
    }
    @Test void rejectsUnknownShapesAndOversizedOrDeepProjectionOutput() {
        for (String invalid : new String[] {"null", "{}", "{\"type\":\"image\"}", "{\"type\":\"text\",\"text\":2}", "{\"type\":\"paragraph\",\"content\":{}}"})
            assertThrows(YjsEngineException.class, () -> RichSearchText.extract(json.readTree(invalid)));
        var tooLarge = json.createObjectNode().put("type", "text").put("text", "x".repeat(32769));
        assertThrows(YjsEngineException.class, () -> RichSearchText.extract(tooLarge));
        var deep = json.createObjectNode().put("type", "doc"); var parent = deep;
        for (int i = 0; i < 34; i++) {
            var child = json.createObjectNode().put("type", "listItem"); parent.putArray("content").add(child); parent = child;
        }
        assertThrows(YjsEngineException.class, () -> RichSearchText.extract(deep));
    }
}
