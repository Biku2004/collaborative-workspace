package com.example.collaborativeworkspace;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentService service;
    public DocumentController(DocumentService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<Document> create(@Valid @RequestBody CreateDocumentRequest request) {
        Document document = service.create(request.title());
        return ResponseEntity.created(URI.create("/api/documents/" + document.id())).body(document);
    }

    @GetMapping("/{id}")
    public Document get(@PathVariable UUID id) { return service.find(id); }
}
