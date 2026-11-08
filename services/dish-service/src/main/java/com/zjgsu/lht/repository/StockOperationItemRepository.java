package com.zjgsu.lht.repository;

import com.zjgsu.lht.model.StockOperationItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockOperationItemRepository extends JpaRepository<StockOperationItem, Long> {
    List<StockOperationItem> findByOrderNoOrderByDishIdAsc(String orderNo);
}
