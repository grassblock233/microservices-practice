package com.zjgsu.lht.client;

import com.zjgsu.lht.exception.ApiException;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class UserClient {
    public record User(String id, String displayName, String status) {
    }

    private final UserFeignClient remote;
    private final DependencyCalls calls;

    public UserClient(UserFeignClient remote, DependencyCalls calls) {
        this.remote = remote;
        this.calls = calls;
    }

    public void requireActive(String id) {
        var response = calls.call("user-service", () -> remote.findById(id), Map.of("USER_NOT_FOUND", 404));
        if (response.getStatusCode().value() != 200) throw DependencyCalls.badResponse();
        var u = response.getBody();
        if (u == null || !id.equals(u.id()) || u.displayName() == null || !("ACTIVE".equals(u.status()) || "DISABLED".equals(u.status())))
            throw DependencyCalls.badResponse();
        if (!"ACTIVE".equals(u.status())) throw new ApiException(409, "USER_DISABLED", "用户已禁用");
    }
}
