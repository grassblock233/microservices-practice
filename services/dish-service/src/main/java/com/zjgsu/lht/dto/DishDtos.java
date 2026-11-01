package com.zjgsu.lht.dto;

import com.zjgsu.lht.model.DishStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public class DishDtos {
    public record Request(
            @NotNull @Positive Long merchantId) {
    }

    public record Response(
            Long id, Long merchantId, String name, String category, BigDecimal price, Integer stock,
            DishStatus status) {
    }
}
