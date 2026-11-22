package com.zjgsu.lht.service;

import com.zjgsu.lht.exception.ApiException;
import com.zjgsu.lht.model.User;
import com.zjgsu.lht.model.User.Role;
import com.zjgsu.lht.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {
    private final UserRepository users;
    private final SecretKey key;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final String dummyHash = bcrypt.encode(UUID.randomUUID().toString());

    public AuthService(UserRepository users, @Value("${JWT_SECRET}") String secret) {
        this.users = users;
        byte[] decoded = Base64.getDecoder().decode(secret);
        if (decoded.length < 64) throw new IllegalArgumentException("JWT_SECRET must decode to at least 64 bytes");
        this.key = Keys.hmacShaKeyFor(decoded);
    }

    public Token login(String username, String password) {
        if (username == null || !username.matches("[A-Za-z0-9_-]{1,64}") || !validPassword(password))
            throw unauthorized();
        User user = users.findByUsername(username.toLowerCase(Locale.ROOT)).orElse(null);
        String hash = user == null ? null : user.passwordHash();
        if (!bcrypt.matches(password, hash == null ? dummyHash : hash) || hash == null
                || user.role() == null || user.status != User.Status.ACTIVE) {
            throw unauthorized();
        }

        Instant now = Instant.now();
        var builder = Jwts.builder().subject(user.id)
                .claim("username", user.username()).claim("role", user.role().name())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(3600)));
        if (user.role() == Role.STUDENT) builder.claim("studentId", user.id);
        String jwt = builder.signWith(key, Jwts.SIG.HS512).compact();
        return new Token(jwt, user.username(), user.role());
    }

    @Bean
    ApplicationRunner initializeDemoAccounts(@Value("${DEMO_STUDENT_PASSWORD:}") String student,
                                             @Value("${DEMO_ADMIN_PASSWORD:}") String admin) {
        return args -> {
            seed("student1", "student-1", Role.STUDENT, student);
            seed("admin", "admin", Role.ADMIN, admin);
        };
    }

    private void seed(String username, String userId, Role role, String password) {
        if (password.isEmpty()) return;
        User user = users.findById(userId).orElseGet(() -> {
            if (role == Role.STUDENT) throw new IllegalStateException("Missing demo user: " + userId);
            User admin = new User();
            admin.id = userId;
            admin.displayName = "演示管理员";
            admin.status = User.Status.ACTIVE;
            return admin;
        });
        if (user.username() != null) return;
        if (!validPassword(password)) throw new IllegalArgumentException("Invalid demo password for " + username);
        user.setCredentials(username, role, bcrypt.encode(password));
        users.save(user);
    }

    private static boolean validPassword(String value) {
        return value != null && !value.isBlank() && value.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    private static ApiException unauthorized() {
        return new ApiException(401, "INVALID_CREDENTIALS", "用户名或密码错误，或账号不可用");
    }

    public record Token(String token, String username, Role role) {
    }
}
