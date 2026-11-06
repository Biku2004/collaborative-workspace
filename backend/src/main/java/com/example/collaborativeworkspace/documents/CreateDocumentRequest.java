package com.example.collaborativeworkspace.documents;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateDocumentRequest(
        @NotBlank @Size(max = 200) @Pattern(regexp = "^[^\\x00]*$") String title,
        @Size(max = 32) @Pattern(regexp = "^(PLAIN_TEXT_V1|RICH_TEXT_V1)$") String format) {}
