package com.example.collaborativeworkspace;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("dev")
public class CsrfController {
    public record CsrfResponse(String headerName, String parameterName, String token) {}

    @GetMapping("/api/csrf")
    public ResponseEntity<CsrfResponse> csrf(CsrfToken csrf) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(csrf.getHeaderName(), csrf.getParameterName(), csrf.getToken()));
    }
}
