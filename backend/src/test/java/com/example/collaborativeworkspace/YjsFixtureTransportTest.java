package com.example.collaborativeworkspace;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class YjsFixtureTransportTest {
    @Test void javaPreservesOpaqueYjsBytesThroughBase64Transport() throws Exception {
        var fixture = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("..", "experiments", "yjs-private-engine", "fixtures", "plain-text.properties"))) {
            fixture.load(reader);
        }
        String encoded = fixture.getProperty("update");
        byte[] opaque = Base64.getDecoder().decode(encoded);
        assertTrue(opaque.length > 0);
        assertEquals(encoded, Base64.getEncoder().encodeToString(opaque));
        assertEquals("Hello Java", fixture.getProperty("text"));
    }
}
