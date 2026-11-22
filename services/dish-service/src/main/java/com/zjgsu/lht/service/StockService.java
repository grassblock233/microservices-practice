package com.zjgsu.lht.service;

import com.zjgsu.lht.dto.StockDtos;
import com.zjgsu.lht.dto.StockDtos.Request;
import com.zjgsu.lht.dto.StockDtos.Snapshot;
import com.zjgsu.lht.exception.ApiException;
import com.zjgsu.lht.model.DishStatus;
import com.zjgsu.lht.repository.DishRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class StockService {
    private static final Logger log = LoggerFactory.getLogger(StockService.class);
    private final DishRepository dishes;
    public StockService(DishRepository dishes) {
        this.dishes = dishes;
    }

    @Transactional
    public List<Snapshot> deduct(Request raw) {
        var request = raw.normalized();
        log.info("event=stock_deduction_requested merchantId={} dishIds={}", request.merchantId(),
                request.items().stream().map(StockDtos.Item::dishId).toList());
        List<Snapshot> snapshots = new ArrayList<>(request.items().size());
        // Normalization sorts IDs so concurrent multi-dish requests acquire locks in the same order.
        for (var item : request.items()) {
            var d = dishes.lockById(item.dishId())
                    .orElseThrow(() -> new ApiException(404, "DISH_NOT_FOUND", "菜品不存在"));
            if (!d.getMerchantId().equals(request.merchantId()))
                throw new ApiException(409, "MERCHANT_MISMATCH", "菜品不属于该商家");
            if (d.getStatus() != DishStatus.ON_SALE) throw new ApiException(409, "DISH_OFF_SALE", "菜品已下架");
            if (d.getStock() < item.quantity()) throw new ApiException(409, "INSUFFICIENT_STOCK", "菜品库存不足");
            d.setStock(d.getStock() - item.quantity());
            snapshots.add(new Snapshot(d.getId(), d.getName(), d.getPrice(), item.quantity()));
        }
        return snapshots;
    }
}
