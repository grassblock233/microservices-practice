package com.zjgsu.lht.model;
import jakarta.persistence.*;
import java.time.LocalDateTime;
@Entity @Table(name="stock_operations")
public class StockOperation {
    @Id @Column(name="order_no",length=36) public String orderNo;
    @Column(name="request_digest",length=64) public String requestDigest;
    @Column(nullable=false,length=32) public String status;
    @Column(name="updated_at",nullable=false,secondPrecision=6) public LocalDateTime updatedAt;
}
