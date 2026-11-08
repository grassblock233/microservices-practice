package com.zjgsu.lht.controller;

import com.zjgsu.lht.dto.UserDtos;
import com.zjgsu.lht.service.UserService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping("/{userId}")
    public UserDtos.Response get(@PathVariable @NotBlank @Size(max = 64) String userId) {
        return service.getUser(userId);
    }
}
