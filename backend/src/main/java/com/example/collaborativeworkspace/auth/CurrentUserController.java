package com.example.collaborativeworkspace.auth;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("dev")
public class CurrentUserController {
    private final UserRepository users;

    public CurrentUserController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/api/me")
    public ResponseEntity<Map<String, String>> currentUser(Authentication authentication) {
        String name = authentication.getName();
        // The development bootstrap principal has no stored account or stable ID.
        var id = users.findIdByEmail(name);
        Map<String, String> body = id.<Map<String, String>>map(value -> Map.of("username", name, "id", value.toString()))
                .orElseGet(() -> Map.of("username", name));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
