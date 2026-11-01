package com.zjgsu.lht.model;
import jakarta.persistence.*;
import java.time.LocalDateTime;
@Entity @Table(name="order_attempts")
public class OrderAttempt {
    @Id @Column(name="order_no",length=36) public String orderNo;
    @Column(name="user_id",nullable=false,length=64) public String userId;
    @Column(name="merchant_id",nullable=false) public Long merchantId;
    @Lob @Column(name="request_items",nullable=false,columnDefinition="TEXT") public String requestItems;
    @Column(nullable=false,length=32) public String status;
    @Column(name="error_code",length=64) public String errorCode;
    @Column(name="failure_detail",length=1000) public String failureDetail;
    @Column(name="updated_at",nullable=false,secondPrecision=6) public LocalDateTime updatedAt;
}
