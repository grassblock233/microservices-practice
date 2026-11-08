package com.zjgsu.lht.client;

import com.zjgsu.lht.exception.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class UserClient {
    public record User(String id, String displayName, String status) {
    }

    private final RestTemplate http;
    private final ServiceLocator services;
    private final DependencyCalls calls;

    public UserClient(RestTemplate http, ServiceLocator services, DependencyCalls calls) {
        this.http = http;
        this.services = services;
        this.calls = calls;
    }

    public void requireActive(String id) {
        var response = calls.call(() -> http.getForEntity(services.baseUrl("user-service") + "/api/users/{id}", User.class, id), Map.of("USER_NOT_FOUND", 404));
        if (response.getStatusCode().value() != 200) throw DependencyCalls.badResponse();
        var u = response.getBody();
        if (u == null || !id.equals(u.id()) || u.displayName() == null || !("ACTIVE".equals(u.status()) || "DISABLED".equals(u.status())))
            throw DependencyCalls.badResponse();
        if (!"ACTIVE".equals(u.status())) throw new ApiException(409, "USER_DISABLED", "用户已禁用");
    }
}
