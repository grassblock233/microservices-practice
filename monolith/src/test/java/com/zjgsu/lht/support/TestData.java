package com.zjgsu.lht.support;

import com.zjgsu.lht.dto.OrderDtos;
import com.zjgsu.lht.model.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 集中构造各层测试所需的数据；实体不预设主键，保存后使用数据库生成的 ID 建立关联。
 */
public final class TestData {

    /**
     * 禁止实例化，仅通过静态工厂方法构造测试数据。
     */
    private TestData() {
    }

    /**
     * 按指定商家、名称、单价、库存和上下架状态构造尚未保存的菜品。
     */
    public static Dish dish(Long merchantId, String name, String price, int stock, DishStatus status) {
        Dish dish = new Dish();
        dish.setMerchantId(merchantId);
        dish.setName(name);
        dish.setCategory("主食");
        dish.setPrice(new BigDecimal(price));
        dish.setStock(stock);
        dish.setStatus(status);
        return dish;
    }

    /**
     * 构造指定学生和创建时间的待支付订单，生成 UUID 订单号以避免测试数据重复。
     */
    public static Order order(String userId, LocalDateTime createdAt) {
        Order order = new Order();
        order.setOrderNo(UUID.randomUUID().toString());
        order.setUserId(userId);
        order.setMerchantId(10L);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setTotalAmount(new BigDecimal("12.50"));
        order.setDeliveryAddress("学生宿舍 1 幢");
        order.setCreatedAt(createdAt);
        return order;
    }

    /**
     * 为已保存的订单和菜品构造明细，记录名称、单价及按数量计算的小计快照。
     */
    public static OrderItem item(Order order, Dish dish, int quantity) {
        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setDishId(dish.getId());
        item.setDishName(dish.getName());
        item.setUnitPrice(dish.getPrice());
        item.setQuantity(quantity);
        item.setSubtotal(dish.getPrice().multiply(BigDecimal.valueOf(quantity)));
        return item;
    }

    /**
     * 将指定购买明细组合成合法请求，统一使用默认学生、商家、配送地址和备注。
     */
    public static OrderDtos.Request request(OrderDtos.ItemRequest... items) {
        return new OrderDtos.Request("student-1", 10L, List.of(items), "学生宿舍 1 幢", "少辣");
    }
}
