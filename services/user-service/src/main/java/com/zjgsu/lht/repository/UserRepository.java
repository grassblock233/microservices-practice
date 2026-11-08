package com.zjgsu.lht.repository;

import com.zjgsu.lht.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, String> {
}
