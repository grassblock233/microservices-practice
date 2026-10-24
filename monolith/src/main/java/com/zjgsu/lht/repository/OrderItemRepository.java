package com.zjgsu.lht.repository;

import com.zjgsu.lht.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    List<OrderItem> findByOrderIdOrderByDishIdAsc(Long orderId);
}