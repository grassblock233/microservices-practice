package com.zjgsu.lht.client;

import com.zjgsu.lht.dto.StockDtos;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(name = "dish-service")
public interface DishFeignClient {
    @PostMapping("/internal/stock/deduct")
    ResponseEntity<List<StockDtos.Snapshot>> deduct(@RequestBody StockDtos.Request request);
}
