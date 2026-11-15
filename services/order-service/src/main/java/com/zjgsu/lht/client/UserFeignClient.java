package com.zjgsu.lht.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "user-service")
public interface UserFeignClient {
    @GetMapping("/api/users/{id}")
    ResponseEntity<UserClient.User> findById(@PathVariable("id") String id);
}
