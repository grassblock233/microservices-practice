package com.zjgsu.lht.repository;

import com.zjgsu.lht.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByOrderNo(String orderNo);

    List<Order> findByUserIdOrderByCreatedAtDescIdDesc(String userId);
}
