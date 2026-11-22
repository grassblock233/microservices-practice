package com.zjgsu.lht.controller;

import com.zjgsu.lht.model.User;
import com.zjgsu.lht.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "JWT_SECRET=" + AuthControllerTest.TEST_SECRET)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {
    // Test-only 64-byte key, shared with UserControllerTest to reuse the Spring context.
    static final String TEST_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZg==";

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @BeforeEach
    void setup() {
        users.deleteAll();
    }

    @Test
    void authenticatesBothRolesAndRejectsUnknownAccountWrongPasswordAndDisabledStudent() throws Exception {
        var password = UUID.randomUUID().toString();
        var hash = new BCryptPasswordEncoder().encode(password);
        var student = new User();
        student.id = "student-1";
        student.displayName = "测试用户";
        student.status = User.Status.ACTIVE;
        student.setCredentials("student1", User.Role.STUDENT, hash);
        users.save(student);
        var admin = new User();
        admin.id = "admin";
        admin.displayName = "演示管理员";
        admin.status = User.Status.ACTIVE;
        admin.setCredentials("admin", User.Role.ADMIN, hash);
        users.save(admin);

        for (var name : new String[]{"student1", "admin"}) {
            var role = name.equals("admin") ? "ADMIN" : "STUDENT";
            var result = mvc.perform(post("/api/auth/login").contentType("application/json").content(login(name, password)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.role").value(role))
                    .andReturn();
            String token = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.token");
            var jwt = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(TEST_SECRET)))
                    .build().parseSignedClaims(token);
            assertEquals("HS512", jwt.getHeader().getAlgorithm());
            assertNull(jwt.getPayload().getIssuer());
            assertEquals(role, jwt.getPayload().get("role", String.class));
            assertEquals(name, jwt.getPayload().get("username", String.class));
            assertEquals(name.equals("admin") ? "admin" : student.id, jwt.getPayload().getSubject());
            assertEquals(name.equals("admin") ? null : student.id, jwt.getPayload().get("studentId", String.class));
            assertEquals(3600_000, jwt.getPayload().getExpiration().getTime() - jwt.getPayload().getIssuedAt().getTime());
        }
        rejectLogin("unknown", password);
        rejectLogin("student1", UUID.randomUUID().toString());
        student.status = User.Status.DISABLED;
        users.save(student);
        rejectLogin("student1", password);
        admin.status = User.Status.DISABLED;
        users.save(admin);
        rejectLogin("admin", password);
    }

    @Test
    void keepsProfileOnlyUsersQueryableAndDoesNotExposeCredentials() throws Exception {
        var profile = new User();
        profile.id = "student-2";
        profile.displayName = "未配置登录的用户";
        profile.status = User.Status.ACTIVE;
        users.save(profile);
        var account = new User();
        account.id = "student-1";
        account.displayName = "已配置登录的用户";
        account.status = User.Status.ACTIVE;
        account.setCredentials("student1", User.Role.STUDENT, new BCryptPasswordEncoder().encode(UUID.randomUUID().toString()));
        users.save(account);

        rejectLogin("student-2", UUID.randomUUID().toString());
        for (var user : new User[]{profile, account}) {
            mvc.perform(get("/api/users/{id}", user.id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(user.id))
                    .andExpect(jsonPath("$.displayName").value(user.displayName))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.username").doesNotHaveJsonPath())
                    .andExpect(jsonPath("$.role").doesNotHaveJsonPath())
                    .andExpect(jsonPath("$.passwordHash").doesNotHaveJsonPath());
        }
    }

    private void rejectLogin(String username, String password) throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content(login(username, password)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    private static String login(String username, String password) {
        return "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password);
    }
}
