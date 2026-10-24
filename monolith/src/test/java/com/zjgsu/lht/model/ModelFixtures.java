package com.zjgsu.lht.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 创建测试数据
 * */
final class ModelFixtures {

    private ModelFixtures() {
    }

    static Dish dish() {
        Dish dish = new Dish();
        dish.setMerchantId(10L);
        dish.setName("鸡腿饭");
        dish.setCategory("主食");
        dish.setPrice(new BigDecimal("12.50"));
        dish.setStock(20);
        dish.setStatus(DishStatus.ON_SALE);
        return dish;
    }

    static Order order() {
        Order order = new Order();
        order.setOrderNo(UUID.randomUUID().toString());
        order.setUserId("student-1");
        order.setMerchantId(10L);
        order.setTotalAmount(new BigDecimal("25.00"));
        order.setDeliveryAddress("学生宿舍 1 幢");
        order.setRemark("少辣");
        order.setCreatedAt(LocalDateTime.of(2026, 10, 24, 12, 30, 0, 123456000));
        return order;
    }

    static OrderItem item(Long orderId, Long dishId) {
        OrderItem item = new OrderItem();
        item.setOrderId(orderId);
        item.setDishId(dishId);
        item.setDishName("鸡腿饭");
        item.setUnitPrice(new BigDecimal("12.50"));
        item.setQuantity(2);
        item.setSubtotal(new BigDecimal("25.00"));
        return item;
    }
}
