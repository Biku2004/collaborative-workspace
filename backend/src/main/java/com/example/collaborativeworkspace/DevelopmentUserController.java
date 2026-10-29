package com.example.collaborativeworkspace;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("dev")
@RequestMapping("/api/dev/users")
public class DevelopmentUserController {
    private final UserService service;
    public DevelopmentUserController(UserService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<UserAccount> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }
}
