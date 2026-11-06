package com.example.collaborativeworkspace;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateRevisionRequest(@NotBlank @Size(max = 100) @Pattern(regexp = "^[^\\p{Cntrl}]+$") String name, @NotNull UUID requestId) {}
