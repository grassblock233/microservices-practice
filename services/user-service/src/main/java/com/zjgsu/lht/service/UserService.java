package com.zjgsu.lht.service;

import com.zjgsu.lht.dto.UserDtos;
import com.zjgsu.lht.exception.ApiException;
import com.zjgsu.lht.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public UserDtos.Response getUser(String id) {
        var u = users.findById(id).orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "用户不存在"));
        return new UserDtos.Response(u.id, u.displayName, u.status);
    }
}
