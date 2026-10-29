package com.example.collaborativeworkspace;

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
    @GetMapping("/api/me")
    public ResponseEntity<Map<String, String>> currentUser(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("username", authentication.getName()));
    }
}
