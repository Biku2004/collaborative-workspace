package com.example.collaborativeworkspace;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record RenameDocumentRequest(@NotBlank @Size(max = 200) @Pattern(regexp = "^[^\\x00]*$") String title,
                                    @NotNull @PositiveOrZero Long version) {}
