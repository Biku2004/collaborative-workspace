package com.example.collaborativeworkspace;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDocumentRequest(@NotBlank @Size(max = 200) String title) {}
