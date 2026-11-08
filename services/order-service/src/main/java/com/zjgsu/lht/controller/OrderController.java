package com.zjgsu.lht.controller;

import com.zjgsu.lht.dto.OrderDtos;
import com.zjgsu.lht.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<OrderDtos.Response> create(@Valid @RequestBody OrderDtos.Request request) {
        var result = service.createOrder(request);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequestUri().path("/{id}")
                .buildAndExpand(result.orderNo()).toUri()).body(result);
    }

    @GetMapping
    public List<OrderDtos.SummaryResponse> list(@RequestParam @NotBlank @Size(max = 64) String userId) {
        return service.getOrders(userId);
    }

    @GetMapping("/{orderNo}")
    public OrderDtos.DetailResponse get(@PathVariable String orderNo) {
        return service.getOrder(orderNo);
    }
}
