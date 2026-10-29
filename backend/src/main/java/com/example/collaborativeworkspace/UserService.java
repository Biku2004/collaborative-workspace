package com.example.collaborativeworkspace;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@Profile("dev")
public class UserService {
    private final UserRepository repository;
    private final PasswordEncoder encoder;
    public UserService(UserRepository repository, PasswordEncoder encoder) { this.repository = repository; this.encoder = encoder; }

    public UserAccount create(CreateUserRequest request) {
        String password = request.getPassword();
        if (password.getBytes(StandardCharsets.UTF_8).length > 72 || password.indexOf('\0') >= 0) throw new InvalidUserInputException();
        String normalizedEmail = request.getEmail().toLowerCase(Locale.ROOT);
        return repository.create(UUID.randomUUID(), normalizedEmail, encoder.encode(password));
    }
}
