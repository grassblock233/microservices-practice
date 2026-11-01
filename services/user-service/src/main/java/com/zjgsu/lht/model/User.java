package com.zjgsu.lht.model;
import jakarta.persistence.*;
@Entity
@Table(name = "users")
public class User {
    @Id @Column(length = 64) public String id;
    @Column(name = "display_name", nullable = false, length = 100) public String displayName;
    @Column(nullable = false, length = 32) @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
    public Status status;
    public enum Status { ACTIVE, DISABLED }
}
