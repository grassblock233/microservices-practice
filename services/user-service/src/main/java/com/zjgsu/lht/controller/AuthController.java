package com.zjgsu.lht.controller;

import com.zjgsu.lht.service.AuthService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<AuthService.Token> login(@RequestBody Login request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(auth.login(request.username(), request.password()));
    }

    public record Login(String username, String password) {
    }
}
