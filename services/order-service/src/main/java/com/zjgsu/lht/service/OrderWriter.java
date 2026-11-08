package com.zjgsu.lht.service;

import com.zjgsu.lht.dto.OrderDtos;
import com.zjgsu.lht.dto.StockDtos;
import com.zjgsu.lht.model.Order;
import com.zjgsu.lht.model.OrderItem;
import com.zjgsu.lht.repository.OrderAttemptRepository;
import com.zjgsu.lht.repository.OrderItemRepository;
import com.zjgsu.lht.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class OrderWriter {
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderAttemptRepository attempts;

    public OrderWriter(OrderRepository orders, OrderItemRepository items, OrderAttemptRepository attempts) {
        this.orders = orders;
        this.items = items;
        this.attempts = attempts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderDtos.Response save(String id, OrderDtos.Request r, List<StockDtos.Snapshot> snapshots) {
        var total = snapshots.stream().map(s -> s.unitPrice().multiply(BigDecimal.valueOf(s.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var o = new Order();
        o.setOrderNo(id);
        o.setUserId(r.userId());
        o.setMerchantId(r.merchantId());
        o.setDeliveryAddress(r.deliveryAddress());
        o.setRemark(r.remark());
        o.setTotalAmount(total);
        o.setCreatedAt(LocalDateTime.now());
        orders.saveAndFlush(o);
        for (var s : snapshots) {
            var i = new OrderItem();
            i.setOrderId(o.getId());
            i.setDishId(s.dishId());
            i.setDishName(s.dishName());
            i.setUnitPrice(s.unitPrice());
            i.setQuantity(s.quantity());
            i.setSubtotal(s.unitPrice().multiply(BigDecimal.valueOf(s.quantity())));
            items.save(i);
        }
        items.flush();
        var a = attempts.findById(id).orElseThrow();
        a.status = "SUCCEEDED";
        a.updatedAt = LocalDateTime.now();
        attempts.saveAndFlush(a);
        return new OrderDtos.Response(id, total, o.getStatus());
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public java.util.Optional<OrderDtos.Response> committed(String id) {
        return orders.findByOrderNo(id).map(o -> new OrderDtos.Response(id, o.getTotalAmount(), o.getStatus()));
    }
}
