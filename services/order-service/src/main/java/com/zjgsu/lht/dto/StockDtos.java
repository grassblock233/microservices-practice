package com.zjgsu.lht.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import com.zjgsu.lht.exception.ApiException;
public class StockDtos {
    public record Item(@NotNull @Positive Long dishId, @NotNull @Positive Integer quantity) {}
    public record Request(@NotNull @Positive Long merchantId,
                          @NotEmpty List<@NotNull @Valid Item> items) {
        public Request normalized() {
            if (merchantId == null || merchantId <= 0 || items == null || items.isEmpty())
                throw new ApiException(400, "INVALID_REQUEST", "商家和菜品明细必填");
            Map<Long,Integer> merged = new TreeMap<>();
            for (var item : items) {
                if (item == null || item.dishId == null || item.dishId <= 0 || item.quantity == null || item.quantity <= 0)
                    throw new ApiException(400, "INVALID_REQUEST", "菜品编号和数量必须为正整数");
                try { merged.merge(item.dishId, item.quantity, Math::addExact); }
                catch (ArithmeticException e) { throw new ApiException(400, "INVALID_REQUEST", "合并数量溢出"); }
            }
            return new Request(merchantId, merged.entrySet().stream().map(e -> new Item(e.getKey(),e.getValue())).toList());
        }
    }
    public record Snapshot(Long dishId, String dishName, BigDecimal unitPrice, Integer quantity) {}
    public record Operation(String orderNo, String status, List<Snapshot> items) {}
}
