package com.zjgsu.lht.dto;

import com.zjgsu.lht.model.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class OrderDtos {

    public record ItemRequest(@NotNull @Positive Long dishId,

                              @NotNull @Positive Integer quantity) {
    }

    public record Request(@NotBlank @Size(max = 64) String userId,

                          @NotNull @Positive Long merchantId,

                          @NotEmpty List<@NotNull @Valid ItemRequest> items,

                          @NotBlank @Size(max = 255) String deliveryAddress,

                          @Size(max = 500) String remark) {
    }

    public record Response(String orderNo, BigDecimal totalAmount, OrderStatus status) {
    }

    public record SummaryResponse(String orderNo, BigDecimal totalAmount, OrderStatus status,
                                  LocalDateTime createdAt) {
    }

    public record ItemResponse(Long dishId, String dishName, BigDecimal unitPrice, Integer quantity,
                               BigDecimal subtotal) {
    }

    public record DetailResponse(String orderNo, String userId, Long merchantId, BigDecimal totalAmount,
                                 OrderStatus status, String deliveryAddress, String remark,
                                 LocalDateTime createdAt, List<ItemResponse> items) {
    }
}
