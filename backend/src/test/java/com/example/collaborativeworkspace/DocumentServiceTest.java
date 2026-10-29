package com.example.collaborativeworkspace;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentServiceTest {
    static class FakeRepository extends DocumentRepository {
        Document stored;
        FakeRepository() { super(null); }
        @Override public Document create(UUID id, String title) { return stored = new Document(id, title, Instant.now()); }
        @Override public Optional<Document> find(UUID id) { return stored != null && stored.id().equals(id) ? Optional.of(stored) : Optional.empty(); }
    }

    @Test void createsAndFindsDocument() {
        var service = new DocumentService(new FakeRepository());
        var document = service.create("  Notes  ");
        assertEquals("Notes", document.title());
        assertEquals(document, service.find(document.id()));
        assertThrows(DocumentNotFoundException.class, () -> service.find(UUID.randomUUID()));
    }

    @Test void validatesTitle() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new CreateDocumentRequest("A title")).isEmpty());
            assertFalse(validator.validate(new CreateDocumentRequest(" ")).isEmpty());
            assertFalse(validator.validate(new CreateDocumentRequest(null)).isEmpty());
            assertFalse(validator.validate(new CreateDocumentRequest("x".repeat(201))).isEmpty());
        }
    }
}
