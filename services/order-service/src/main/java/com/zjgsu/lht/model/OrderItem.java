package com.zjgsu.lht.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

@Entity
@Table(name = "order_items",
        uniqueConstraints = @UniqueConstraint(name = "uk_order_items_order_dish",
                columnNames = {"order_id", "dish_id"}),
        indexes = @Index(name = "idx_order_items_dish_id", columnList = "dish_id"))
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @NotNull
    @Column(name = "dish_id", nullable = false)
    private Long dishId;

    @NotBlank
    @Size(max = 100)
    @Column(name = "dish_name", nullable = false, length = 100)
    private String dishName;

    @NotNull
    @DecimalMin("0")
    @Digits(integer = 17, fraction = 2)
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal unitPrice;

    @NotNull
    @Positive
    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @NotNull
    @DecimalMin("0")
    @Digits(integer = 17, fraction = 2)
    @Column(name = "subtotal", nullable = false, precision = 19, scale = 2)
    private BigDecimal subtotal;

    public OrderItem() {
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getDishId() {
        return dishId;
    }

    public void setDishId(Long dishId) {
        this.dishId = dishId;
    }

    public String getDishName() {
        return dishName;
    }

    public void setDishName(String dishName) {
        this.dishName = dishName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
    }
}
