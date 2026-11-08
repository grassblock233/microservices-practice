package com.zjgsu.lht.repository;

import com.zjgsu.lht.model.Dish;
import com.zjgsu.lht.model.DishStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DishRepository extends JpaRepository<Dish, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select d from Dish d where d.id = :id")
    java.util.Optional<Dish> lockById(@org.springframework.data.repository.query.Param("id") Long id);

    List<Dish> findByMerchantIdAndStatusOrderByIdAsc(Long merchantId, DishStatus status);
}
