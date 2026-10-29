package com.example.collaborativeworkspace;

import java.util.UUID;
import java.util.Optional;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;
    public UserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public UserAccount create(UUID id, String email, String hash) {
        try {
            return jdbc.queryForObject("INSERT INTO app_users (id, email, password_hash) VALUES (?, ?, ?) RETURNING id, email, created_at",
                (rs, row) -> new UserAccount(rs.getObject("id", UUID.class), rs.getString("email"), rs.getTimestamp("created_at").toInstant()),
                id, email, hash);
        } catch (DuplicateKeyException ex) {
            throw new DuplicateUserException();
        }
    }

    public Optional<UserDetails> findForAuthentication(String email) {
        return jdbc.query("SELECT email, password_hash FROM app_users WHERE email = ?",
                (rs, row) -> (UserDetails) User.withUsername(rs.getString("email"))
                        .password(rs.getString("password_hash")).roles("USER").build(), email)
                .stream().findFirst();
    }
}
