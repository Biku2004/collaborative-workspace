package com.example.collaborativeworkspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
public class SecurityConfiguration {

    @Bean
    @Profile("dev")
    PasswordEncoder developmentPasswordEncoder(@Value("${workspace.security.bcrypt-strength:10}") int strength) {
        if (strength < 10 || strength > 16) throw new IllegalArgumentException("BCrypt strength must be between 10 and 16");
        return new BCryptPasswordEncoder(strength);
    }

    @Bean
    @Profile("dev")
    UserDetailsService temporaryDevelopmentUser(@Value("${workspace.dev.password}") String password, PasswordEncoder encoder) {
        if (password.isBlank()) throw new IllegalArgumentException("Development password must not be blank");
        var user = User.withUsername("learner").password(encoder.encode(password)).roles("USER").build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    @Profile("!dev")
    UserDetailsService noTemporaryUsers() {
        return username -> { throw new UsernameNotFoundException("No users configured"); };
    }

    @Bean
    @Profile("dev")
    SecurityFilterChain developmentSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.GET, "/api/status", "/api/csrf").permitAll()
                    .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                    .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults())
                .exceptionHandling(errors -> errors.defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
                .build();
    }

    @Bean
    @Profile("!dev")
    SecurityFilterChain closedByDefault(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.GET, "/api/status").permitAll()
                    .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                    .anyRequest().denyAll())
                .build();
    }
}
