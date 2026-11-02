package com.example.collaborativeworkspace;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class DocumentServiceTest {
    @Test void validatesTitle() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new CreateDocumentRequest("A title")).isEmpty());
            assertFalse(validator.validate(new CreateDocumentRequest(" ")).isEmpty());
            assertFalse(validator.validate(new CreateDocumentRequest(null)).isEmpty());
            assertFalse(validator.validate(new CreateDocumentRequest("x".repeat(201))).isEmpty());
            assertFalse(validator.validate(new CreateDocumentRequest("text\0bad")).isEmpty());
        }
    }
}
