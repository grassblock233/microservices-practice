package com.zjgsu.lht.model;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "stock_operation_items", uniqueConstraints = @UniqueConstraint(columnNames = {"order_no", "dish_id"}))
public class StockOperationItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "order_no", nullable = false, length = 36)
    public String orderNo;
    @Column(name = "dish_id", nullable = false)
    public Long dishId;
    @Column(name = "dish_name", nullable = false, length = 100)
    public String dishName;
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 2)
    public BigDecimal unitPrice;
    @Column(nullable = false)
    public Integer quantity;
}
