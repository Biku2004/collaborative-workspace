package com.example.collaborativeworkspace.documents.history;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RestoreRevisionRequest(@NotNull UUID revisionId, @NotNull UUID requestId,
        @NotNull @Min(0) Long expectedEpoch, @NotNull @Min(0) Long expectedHead) {}
