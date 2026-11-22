package com.zjgsu.lht.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "users")
public class User {
    @Id
    @Column(length = 64)
    public String id;
    @Column(name = "display_name", nullable = false, length = 100)
    public String displayName;
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    public Status status;

    @Column(length = 64, unique = true)
    private String username;
    @Column(length = 16)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Role role;
    @Column(name = "password_hash", length = 60)
    private String passwordHash;

    public String username() {
        return username;
    }

    public Role role() {
        return role;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public void setCredentials(String username, Role role, String passwordHash) {
        this.username = username;
        this.role = role;
        this.passwordHash = passwordHash;
    }

    public enum Status {ACTIVE, DISABLED}

    public enum Role {STUDENT, ADMIN}
}
