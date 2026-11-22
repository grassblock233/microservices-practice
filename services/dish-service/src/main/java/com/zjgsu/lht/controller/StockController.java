package com.zjgsu.lht.controller;

import com.zjgsu.lht.dto.StockDtos.Request;
import com.zjgsu.lht.dto.StockDtos.Snapshot;
import com.zjgsu.lht.service.StockService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/stock")
public class StockController {
    private final StockService stock;

    public StockController(StockService stock) {
        this.stock = stock;
    }

    @PostMapping("/deduct")
    public List<Snapshot> deduct(@Valid @RequestBody Request r) {
        return stock.deduct(r);
    }
}
