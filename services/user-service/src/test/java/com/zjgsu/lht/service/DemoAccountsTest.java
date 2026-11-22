package com.zjgsu.lht.service;

import com.zjgsu.lht.model.User;
import com.zjgsu.lht.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Base64;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DemoAccountsTest {
    @Test
    void fillsMissingCredentialsWithoutReplacingHashesOrChangingStatus() throws Exception {
        var repo = mock(UserRepository.class);
        var rows = new HashMap<String, User>();
        var student = new User();
        student.id = "student-1";
        student.displayName = "原有资料";
        student.status = User.Status.DISABLED;
        rows.put(student.id, student);
        when(repo.findById(any())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
        when(repo.save(any(User.class))).thenAnswer(call -> {
            User user = call.getArgument(0);
            rows.put(user.id, user);
            return user;
        });

        var service = newAuthService(repo);
        String studentPassword = UUID.randomUUID().toString();
        String adminPassword = UUID.randomUUID().toString();
        var args = new DefaultApplicationArguments();
        service.initializeDemoAccounts(studentPassword, adminPassword).run(args);

        var admin = rows.get("admin");
        assertEquals("student1", student.username());
        assertEquals(User.Role.STUDENT, student.role());
        assertEquals("原有资料", student.displayName);
        assertEquals(User.Status.DISABLED, student.status);
        assertEquals("admin", admin.username());
        assertEquals(User.Role.ADMIN, admin.role());
        assertEquals(User.Status.ACTIVE, admin.status);
        var encoder = new BCryptPasswordEncoder();
        String studentHash = student.passwordHash();
        String adminHash = admin.passwordHash();
        assertTrue(encoder.matches(studentPassword, studentHash));
        assertTrue(encoder.matches(adminPassword, adminHash));

        admin.status = User.Status.DISABLED;
        newAuthService(repo).initializeDemoAccounts(UUID.randomUUID().toString(), UUID.randomUUID().toString()).run(args);
        assertEquals(studentHash, rows.get(student.id).passwordHash());
        assertEquals(adminHash, rows.get(admin.id).passwordHash());
        assertEquals(User.Status.DISABLED, rows.get(student.id).status);
        assertEquals(User.Status.DISABLED, rows.get(admin.id).status);
        verify(repo, times(2)).save(any(User.class));
    }

    @Test
    void skipsAccountsWhenDemoPasswordsAreNotConfigured() throws Exception {
        var repo = mock(UserRepository.class);
        newAuthService(repo).initializeDemoAccounts("", "").run(new DefaultApplicationArguments());
        verifyNoInteractions(repo);
    }

    private static AuthService newAuthService(UserRepository repo) {
        return new AuthService(repo, Base64.getEncoder().encodeToString(Jwts.SIG.HS512.key().build().getEncoded()));
    }
}
