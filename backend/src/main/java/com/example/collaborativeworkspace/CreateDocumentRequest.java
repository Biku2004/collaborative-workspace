package com.example.collaborativeworkspace;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateDocumentRequest(@NotBlank @Size(max = 200) @Pattern(regexp = "^[^\\x00]*$") String title) {}
