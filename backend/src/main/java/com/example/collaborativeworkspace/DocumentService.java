package com.example.collaborativeworkspace;

import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DocumentService {
    private final DocumentRepository repository;
    public DocumentService(DocumentRepository repository) { this.repository = repository; }
    public Document create(String title) { return repository.create(UUID.randomUUID(), title.strip()); }
    public Document find(UUID id) { return repository.find(id).orElseThrow(DocumentNotFoundException::new); }
}
