package com.zjgsu.lht.repository;

import com.zjgsu.lht.model.Dish;
import com.zjgsu.lht.model.DishStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DishRepository extends JpaRepository<Dish, Long> {
    List<Dish> findByMerchantIdAndStatusOrderByIdAsc(Long merchantId, DishStatus status);
}
