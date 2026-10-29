package com.example.collaborativeworkspace;

import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

public final class BoundedBcryptPasswordEncoder implements PasswordEncoder {
    private final BCryptPasswordEncoder delegate;

    public BoundedBcryptPasswordEncoder(int strength) { this.delegate = new BCryptPasswordEncoder(strength); }

    private boolean valid(CharSequence raw) {
        return raw != null && raw.toString().indexOf('\0') < 0
                && raw.toString().getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @Override public String encode(CharSequence raw) {
        if (!valid(raw)) throw new IllegalArgumentException("Password is not suitable for BCrypt");
        return delegate.encode(raw);
    }

    @Override public boolean matches(CharSequence raw, String encoded) {
        return valid(raw) && delegate.matches(raw, encoded);
    }

    @Override public boolean upgradeEncoding(String encoded) { return delegate.upgradeEncoding(encoded); }
}
