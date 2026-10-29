package com.example.collaborativeworkspace;

import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@Profile("dev")
public class DevelopmentUserDetailsService implements UserDetailsService {
    private final UserRepository repository;
    private final UserDetails bootstrap;

    public DevelopmentUserDetailsService(UserRepository repository, UserDetails bootstrapUser) {
        this.repository = repository;
        this.bootstrap = bootstrapUser;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if ("learner".equals(username)) return User.withUserDetails(bootstrap).build();
        if (username.length() > 254 || !username.matches("^[\\x21-\\x7E]+$")) {
            throw new UsernameNotFoundException("Invalid credentials");
        }
        return repository.findForAuthentication(username.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}
