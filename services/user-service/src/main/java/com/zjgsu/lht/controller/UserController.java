package com.zjgsu.lht.controller;
import com.zjgsu.lht.dto.UserDtos;
import com.zjgsu.lht.service.UserService;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService service;
    public UserController(UserService service) { this.service = service; }
    @GetMapping("/{userId}")
    public UserDtos.Response get(@PathVariable @NotBlank @Size(max=64) String userId) {
        return service.getUser(userId);
    }
}
