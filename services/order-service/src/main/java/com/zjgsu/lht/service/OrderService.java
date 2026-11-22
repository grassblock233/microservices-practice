package com.zjgsu.lht.service;

import com.zjgsu.lht.client.DishClient;
import com.zjgsu.lht.client.UserClient;
import com.zjgsu.lht.dto.OrderDtos;
import com.zjgsu.lht.dto.StockDtos;
import com.zjgsu.lht.exception.ApiException;
import com.zjgsu.lht.model.Order;
import com.zjgsu.lht.repository.OrderItemRepository;
import com.zjgsu.lht.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final UserClient users;
    private final DishClient dishes;
    private final OrderWriter writer;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    public OrderService(UserClient users, DishClient dishes, OrderWriter writer,
                        OrderRepository orders, OrderItemRepository items) {
        this.users = users;
        this.dishes = dishes;
        this.writer = writer;
        this.orderRepository = orders;
        this.orderItemRepository = items;
    }

    // Keep remote deduction outside the local order transaction.
    public OrderDtos.Response createOrder(OrderDtos.Request r) {
        var stock = new StockDtos.Request(r.merchantId(), r.items().stream()
                .map(i -> new StockDtos.Item(i.dishId(), i.quantity())).toList()).normalized();
        users.requireActive(r.userId());
        var id = UUID.randomUUID().toString();
        List<StockDtos.Snapshot> snapshots;
        log.info("event=order_deduction_requested orderNo={} merchantId={} items={}", id, stock.merchantId(), stock.items());
        try {
            snapshots = dishes.deduct(stock);
        } catch (ApiException e) {
            if (e.status == 404 || e.status == 409) {
                log.info("event=stock_deduction_rejected orderNo={} code={}", id, e.code);
                throw new ApiException(e.status, e.code, e.getMessage(), id, e);
            }
            log.warn("event=stock_deduction_failed orderNo={} code={}", id, e.code, e);
            throw new ApiException(e.status, e.code, e.getMessage() + "；库存可能已扣减，请凭订单号人工核对，勿直接重试", id, e);
        } catch (RuntimeException e) {
            log.warn("event=stock_deduction_failed orderNo={} code=DEPENDENCY_BAD_RESPONSE", id, e);
            throw new ApiException(502, "DEPENDENCY_BAD_RESPONSE", "库存可能已扣减，请凭订单号人工核对，勿直接重试", id, e);
        }
        try {
            var result = writer.save(id, r, snapshots);
            log.info("event=order_created orderNo={}", id);
            return result;
        } catch (RuntimeException failure) {
            log.error("event=order_save_failed orderNo={}", id, failure);
            throw new ApiException(500, "ORDER_SAVE_FAILED", "库存已扣减，订单保存失败或提交结果未确认，请凭订单号人工核对，勿直接重试", id, failure);
        }
    }

    /**
     * 按创建时间倒序查询学生订单，同一时间的订单按内部编号倒序排列
     */
    @Transactional(readOnly = true)
    public List<OrderDtos.SummaryResponse> getOrders(String userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(order -> new OrderDtos.SummaryResponse(order.getOrderNo(), order.getTotalAmount(),
                        order.getStatus(), order.getCreatedAt()))
                .toList();
    }

    /**
     * 仅使用订单及明细中的历史字段生成详情，不读取菜品的当前名称或价格
     */
    @Transactional(readOnly = true)
    public OrderDtos.DetailResponse getOrder(String orderNo, String caller, boolean admin) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new ApiException(404, "ORDER_NOT_FOUND", "订单不存在"));
        if (!admin && !order.getUserId().equals(caller)) {
            throw new ApiException(403, "FORBIDDEN", "无权访问该订单");
        }
        List<OrderDtos.ItemResponse> items = orderItemRepository
                .findByOrderIdOrderByDishIdAsc(order.getId()).stream()
                .map(item -> new OrderDtos.ItemResponse(item.getDishId(), item.getDishName(),
                        item.getUnitPrice(), item.getQuantity(), item.getSubtotal()))
                .toList();
        return new OrderDtos.DetailResponse(order.getOrderNo(), order.getUserId(), order.getMerchantId(),
                order.getTotalAmount(), order.getStatus(), order.getDeliveryAddress(), order.getRemark(),
                order.getCreatedAt(), items);
    }


}
