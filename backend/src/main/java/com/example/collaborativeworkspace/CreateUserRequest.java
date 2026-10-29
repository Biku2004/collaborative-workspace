package com.example.collaborativeworkspace;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class CreateUserRequest {
    @NotBlank @Email @Size(max = 254) @Pattern(regexp = "^[\\x21-\\x7E]+$")
    private String email;
    @NotBlank @Size(min = 12, max = 72)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
