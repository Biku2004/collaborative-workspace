package com.example.collaborativeworkspace;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RichTextContractTest {
    @Test void packagedContractProvidesDefensivelyCopiedBootstrapBytes() {
        var contract = new RichTextContract(JsonMapper.builder().build());
        byte[] first = contract.initialUpdate(), second = contract.initialUpdate();
        assertTrue(first.length > 0); assertArrayEquals(first, second);
        first[0] ^= 1;
        assertNotEquals(first[0], contract.initialUpdate()[0]);
    }
}
