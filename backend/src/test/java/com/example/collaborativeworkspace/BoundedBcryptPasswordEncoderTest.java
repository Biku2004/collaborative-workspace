package com.example.collaborativeworkspace;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BoundedBcryptPasswordEncoderTest {
    @Test void rejectsLongOrNulPasswordsInsteadOfAcceptingATruncatedMatch() {
        var encoder = new BoundedBcryptPasswordEncoder(10);
        String password = "x".repeat(72);
        String hash = encoder.encode(password);
        assertTrue(encoder.matches(password, hash));
        assertFalse(encoder.matches(password + "extra", hash));
        assertFalse(encoder.matches(password + "\0", hash));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode("é".repeat(37)));
    }
}
