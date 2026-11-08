package com.zjgsu.lht.dto;

import com.zjgsu.lht.model.User;

public class UserDtos {
    public record Response(String id, String displayName, User.Status status) {
    }
}
