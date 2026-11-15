package com.zjgsu.lht.client;

import com.zjgsu.lht.dto.StockDtos;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(name = "dish-service")
public interface DishFeignClient {
    @PostMapping("/internal/dishes/validate")
    ResponseEntity<List<StockDtos.Snapshot>> validate(@RequestBody StockDtos.Request request);

    @PostMapping("/internal/stock-operations/{orderNo}/deduct")
    ResponseEntity<StockDtos.Operation> deduct(@PathVariable("orderNo") String orderNo,
                                               @RequestBody StockDtos.Request request);

    @PutMapping("/internal/stock-operations/{orderNo}/release")
    ResponseEntity<StockDtos.Operation> release(@PathVariable("orderNo") String orderNo);
}
