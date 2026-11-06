package com.example.collaborativeworkspace.documents;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class DocumentServiceTest {
    @Test void validatesTitle() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new CreateDocumentRequest("A title", null)).isEmpty());
            assertTrue(validator.validate(new CreateDocumentRequest("A title", "PLAIN_TEXT_V1")).isEmpty());
            assertTrue(validator.validate(new CreateDocumentRequest("A title", "RICH_TEXT_V1")).isEmpty());
            assertFalse(validator.validate(new CreateDocumentRequest("A title", "RICH_TEXT_V2")).isEmpty());
            assertFalse(validator.validate(new CreateDocumentRequest(" ", null)).isEmpty());
            assertFalse(validator.validate(new CreateDocumentRequest(null, null)).isEmpty());
            assertFalse(validator.validate(new CreateDocumentRequest("x".repeat(201), null)).isEmpty());
            assertFalse(validator.validate(new CreateDocumentRequest("text\0bad", null)).isEmpty());
        }
    }
}
