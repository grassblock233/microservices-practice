package com.zjgsu.lht.service;

import com.zjgsu.lht.dto.OrderDtos;
import com.zjgsu.lht.exception.InvalidRequestException;
import com.zjgsu.lht.exception.OrderConflictException;
import com.zjgsu.lht.exception.ResourceNotFoundException;
import com.zjgsu.lht.model.*;
import com.zjgsu.lht.repository.DishRepository;
import com.zjgsu.lht.repository.OrderItemRepository;
import com.zjgsu.lht.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class OrderService {

    private final DishRepository dishRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    public OrderService(DishRepository dishRepository, OrderRepository orderRepository,
                        OrderItemRepository orderItemRepository) {
        this.dishRepository = dishRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
    }

    /**
     * 合并重复菜品并完成全部校验后，按数据库中的售价创建待支付订单和快照，订单、明细及库存变更共享同一事务
     */
    @Transactional
    public OrderDtos.Response createOrder(OrderDtos.Request request) {
        Map<Long, Integer> quantities = mergeQuantities(request.items());
        List<Dish> dishes = new ArrayList<>();
        List<OrderItem> items = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        // 校验所有菜品
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            Dish dish = dishRepository.findById(entry.getKey())
                    .orElseThrow(() -> new ResourceNotFoundException("菜品不存在：" + entry.getKey()));
            validateDish(dish, request.merchantId(), entry.getValue());
            OrderItem item = createItem(dish, entry.getValue());
            totalAmount = totalAmount.add(item.getSubtotal());
            dishes.add(dish);
            items.add(item);
        }

        Order order = new Order();
        order.setOrderNo(UUID.randomUUID().toString());
        order.setUserId(request.userId());
        order.setMerchantId(request.merchantId());
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setTotalAmount(totalAmount);
        order.setDeliveryAddress(request.deliveryAddress());
        order.setRemark(request.remark());
        order.setCreatedAt(LocalDateTime.now());
        order = orderRepository.save(order);

        for (OrderItem item : items) {
            item.setOrderId(order.getId());
        }
        for (Dish dish : dishes) {
            dish.setStock(dish.getStock() - quantities.get(dish.getId()));
        }

        orderItemRepository.saveAll(items);
        return new OrderDtos.Response(order.getOrderNo(), order.getTotalAmount(), order.getStatus());
    }

    /**
     * 按创建时间倒序查询学生订单，同一时间的订单按内部编号倒序排列
     */
    public List<OrderDtos.SummaryResponse> getOrders(String userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(order -> new OrderDtos.SummaryResponse(order.getOrderNo(), order.getTotalAmount(),
                        order.getStatus(), order.getCreatedAt()))
                .toList();
    }

    /**
     * 仅使用订单及明细中的历史字段生成详情，不读取菜品的当前名称或价格
     */
    public OrderDtos.DetailResponse getOrder(String orderNo) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new ResourceNotFoundException("订单不存在：" + orderNo));
        List<OrderDtos.ItemResponse> items = orderItemRepository
                .findByOrderIdOrderByDishIdAsc(order.getId()).stream()
                .map(item -> new OrderDtos.ItemResponse(item.getDishId(), item.getDishName(),
                        item.getUnitPrice(), item.getQuantity(), item.getSubtotal()))
                .toList();
        return new OrderDtos.DetailResponse(order.getOrderNo(), order.getUserId(), order.getMerchantId(),
                order.getTotalAmount(), order.getStatus(), order.getDeliveryAddress(), order.getRemark(),
                order.getCreatedAt(), items);
    }

    /**
     * 合并相同菜品的数量，并拒绝超出 Integer 范围的 item 数量防溢出
     */
    private Map<Long, Integer> mergeQuantities(List<OrderDtos.ItemRequest> items) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (OrderDtos.ItemRequest item : items) {
            try {
                quantities.merge(item.dishId(), item.quantity(), Math::addExact);
            } catch (ArithmeticException exception) {
                throw new InvalidRequestException("菜品购买数量超出允许范围：" + item.dishId());
            }
        }
        return quantities;
    }

    /**
     * 下单资格规则 R1 的实现
     */
    private void validateDish(Dish dish, Long merchantId, int quantity) {
        if (!dish.getMerchantId().equals(merchantId)) {
            throw new OrderConflictException("菜品不属于请求商家：" + dish.getId());
        }
        if (dish.getStatus() != DishStatus.ON_SALE) {
            throw new OrderConflictException("菜品已下架：" + dish.getId());
        }
        if (dish.getStock() < quantity) {
            throw new OrderConflictException("菜品库存不足：" + dish.getId());
        }
    }

    /**
     * 生成快照
     */
    private OrderItem createItem(Dish dish, int quantity) {
        OrderItem item = new OrderItem();
        item.setDishId(dish.getId());
        item.setDishName(dish.getName());
        item.setUnitPrice(dish.getPrice());
        item.setQuantity(quantity);
        item.setSubtotal(dish.getPrice().multiply(BigDecimal.valueOf(quantity)));
        return item;
    }
}
