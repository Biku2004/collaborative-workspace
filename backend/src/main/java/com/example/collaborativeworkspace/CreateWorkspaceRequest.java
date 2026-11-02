package com.example.collaborativeworkspace;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateWorkspaceRequest(@NotBlank @Size(max = 120) @Pattern(regexp = "^[^\\x00]*$") String name) {}
