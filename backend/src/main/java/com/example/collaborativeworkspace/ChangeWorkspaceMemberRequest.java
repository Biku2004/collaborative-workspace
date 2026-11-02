package com.example.collaborativeworkspace;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChangeWorkspaceMemberRequest(
        @NotBlank @Email @Size(max = 254) @Pattern(regexp = "^[\\x21-\\x7E]+$") String email,
        @NotNull WorkspaceRole role) {}
