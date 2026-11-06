package com.example.collaborativeworkspace.documents;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ArchiveDocumentRequest(@NotNull @PositiveOrZero Long version) {}
