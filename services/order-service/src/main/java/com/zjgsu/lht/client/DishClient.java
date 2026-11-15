package com.zjgsu.lht.client;

import com.zjgsu.lht.dto.StockDtos.Operation;
import com.zjgsu.lht.dto.StockDtos.Request;
import com.zjgsu.lht.dto.StockDtos.Snapshot;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DishClient {
    private static final Map<String, Integer> RULES = Map.of("DISH_NOT_FOUND", 404, "DISH_OFF_SALE", 409,
            "MERCHANT_MISMATCH", 409, "INSUFFICIENT_STOCK", 409, "OPERATION_CONFLICT", 409, "OPERATION_RELEASED", 409);
    private final DishFeignClient remote;
    private final DependencyCalls calls;

    public DishClient(DishFeignClient remote, DependencyCalls calls) {
        this.remote = remote;
        this.calls = calls;
    }

    public void validate(Request request) {
        var response = calls.call("dish-service", () -> remote.validate(request), RULES);
        if (response.getStatusCode().value() != 200) throw DependencyCalls.badResponse();
        check(response.getBody(), request);
    }

    public Operation deduct(String orderNo, Request request) {
        var response = calls.call("dish-service", () -> remote.deduct(orderNo, request), RULES);
        if (response.getStatusCode().value() != 200 && response.getStatusCode().value() != 201)
            throw DependencyCalls.badResponse();
        var op = response.getBody();
        if (op == null || !orderNo.equals(op.orderNo()) || !"DEDUCTED".equals(op.status()))
            throw DependencyCalls.badResponse();
        check(op.items(), request);
        return op;
    }

    public void release(String orderNo) {
        var response = calls.call("dish-service", () -> remote.release(orderNo), Map.of());
        if (response.getStatusCode().value() != 200) throw DependencyCalls.badResponse();
        var op = response.getBody();
        if (op == null || !orderNo.equals(op.orderNo()) || !"RELEASED".equals(op.status()))
            throw DependencyCalls.badResponse();
    }

    private void check(List<Snapshot> list, Request request) {
        if (list == null || list.size() != request.items().size()) throw DependencyCalls.badResponse();
        Map<Long, Integer> expected = new HashMap<>();
        for (var i : request.items()) expected.put(i.dishId(), i.quantity());
        for (var s : list) {
            if (s == null || s.dishId() == null || s.quantity() == null || !s.quantity().equals(expected.remove(s.dishId())) ||
                    s.dishName() == null || s.dishName().isBlank() || s.dishName().length() > 100 || s.unitPrice() == null ||
                    s.unitPrice().signum() < 0 || s.unitPrice().scale() > 2 || s.unitPrice().precision() - s.unitPrice().scale() > 17)
                throw DependencyCalls.badResponse();
        }
        if (!expected.isEmpty()) throw DependencyCalls.badResponse();
    }
}
