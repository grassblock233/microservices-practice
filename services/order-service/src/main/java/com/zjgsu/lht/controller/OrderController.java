package com.zjgsu.lht.controller;

import com.zjgsu.lht.dto.OrderDtos;
import com.zjgsu.lht.exception.ApiException;
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
    private static final String USER_ID = "X-User-Id";
    private static final String ROLE = "X-User-Role";
    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<OrderDtos.Response> create(@Valid @RequestBody OrderDtos.Request request,
            @RequestHeader(value = USER_ID, required = false) String caller,
            @RequestHeader(value = ROLE, required = false) String role) {
        if (!"STUDENT".equals(role) || !request.userId().equals(caller)) throw forbidden();
        var result = service.createOrder(request);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequestUri().path("/{id}")
                .buildAndExpand(result.orderNo()).toUri()).body(result);
    }

    @GetMapping
    public List<OrderDtos.SummaryResponse> list(@RequestParam @NotBlank @Size(max = 64) String userId,
            @RequestHeader(value = USER_ID, required = false) String caller,
            @RequestHeader(value = ROLE, required = false) String role) {
        if (!isAdmin(caller, role) && !("STUDENT".equals(role) && userId.equals(caller))) throw forbidden();
        return service.getOrders(userId);
    }

    @GetMapping("/{orderNo}")
    public OrderDtos.DetailResponse get(@PathVariable String orderNo,
            @RequestHeader(value = USER_ID, required = false) String caller,
            @RequestHeader(value = ROLE, required = false) String role) {
        boolean admin = isAdmin(caller, role);
        if (!admin && !("STUDENT".equals(role) && caller != null && !caller.isBlank())) throw forbidden();
        return service.getOrder(orderNo, caller, admin);
    }

    private static boolean isAdmin(String caller, String role) {
        return caller != null && !caller.isBlank() && "ADMIN".equals(role);
    }

    private static ApiException forbidden() {
        return new ApiException(403, "FORBIDDEN", "无权访问该订单");
    }
}
