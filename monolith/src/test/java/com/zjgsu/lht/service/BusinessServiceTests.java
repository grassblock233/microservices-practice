package com.zjgsu.lht.service;

import com.zjgsu.lht.dto.DishDtos;
import com.zjgsu.lht.dto.OrderDtos;
import com.zjgsu.lht.exception.InvalidRequestException;
import com.zjgsu.lht.exception.OrderConflictException;
import com.zjgsu.lht.exception.ResourceNotFoundException;
import com.zjgsu.lht.model.Dish;
import com.zjgsu.lht.model.DishStatus;
import com.zjgsu.lht.model.Order;
import com.zjgsu.lht.model.OrderStatus;
import com.zjgsu.lht.repository.DishRepository;
import com.zjgsu.lht.repository.OrderItemRepository;
import com.zjgsu.lht.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
class BusinessServiceTests {

    @Autowired
    private OrderService orderService;
    @Autowired
    private DishService dishService;
    @Autowired
    private DishRepository dishRepository;
    @Autowired
    private OrderRepository orderRepository;
    @MockitoSpyBean
    private OrderItemRepository orderItemRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        orderItemRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        dishRepository.deleteAllInBatch();
    }

    /** 验证重复菜品只生成一条明细，金额精确计算且订单、明细和库存实际提交。 */
    @Test
    void createsOrderWithMergedItemsAndAccurateSnapshots() {
        Dish rice = dish(10L, "鸡腿饭", "12.50", 3, DishStatus.ON_SALE);
        Dish drink = dish(10L, "饮料", "3.20", 8, DishStatus.ON_SALE);
        OrderDtos.Response response = orderService.createOrder(request("student-1",
                item(rice, 1), item(drink, 2), item(rice, 2)));

        assertThat(UUID.fromString(response.orderNo()).toString()).isEqualTo(response.orderNo());
        assertThat(response.totalAmount()).isEqualByComparingTo("43.90");
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(orderItemRepository.count()).isEqualTo(2);
        assertThat(dishRepository.findById(rice.getId()).orElseThrow().getStock()).isZero();
        assertThat(dishRepository.findById(drink.getId()).orElseThrow().getStock()).isEqualTo(6);

        OrderDtos.DetailResponse detail = orderService.getOrder(response.orderNo());
        assertThat(detail.userId()).isEqualTo("student-1");
        assertThat(detail.merchantId()).isEqualTo(10L);
        assertThat(detail.deliveryAddress()).isEqualTo("学生宿舍 1 幢");
        assertThat(detail.remark()).isEqualTo("少辣");
        assertThat(detail.createdAt()).isNotNull();
        assertThat(detail.items()).extracting(OrderDtos.ItemResponse::dishId)
                .containsExactly(rice.getId(), drink.getId());
        assertThat(detail.items().getFirst().quantity()).isEqualTo(3);
        assertThat(detail.items().getFirst().dishName()).isEqualTo("鸡腿饭");
        assertThat(detail.items().getFirst().unitPrice()).isEqualByComparingTo("12.50");
        assertThat(detail.items().getFirst().subtotal()).isEqualByComparingTo("37.50");
    }

    /** 零单价属于合法边界，允许生成零金额订单并正常扣减库存。 */
    @Test
    void acceptsZeroPrice() {
        Dish freeDish = dish(10L, "赠品", "0.00", 1, DishStatus.ON_SALE);
        OrderDtos.Response response = orderService.createOrder(request("student-1", item(freeDish, 1)));
        assertThat(response.totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dishRepository.findById(freeDish.getId()).orElseThrow().getStock()).isZero();
    }

    @Test
    void missingDishRejectsTheWholeOrder() {
        Dish valid = dish(10L, "鸡腿饭", "12.50", 20, DishStatus.ON_SALE);
        assertThatThrownBy(() -> orderService.createOrder(request("student-1", item(valid, 2),
                new OrderDtos.ItemRequest(Long.MAX_VALUE, 1))))
                .isInstanceOf(ResourceNotFoundException.class).hasMessageContaining("菜品不存在");
        assertNoOrderAndUnchangedStock(valid, 20);
    }

    @Test
    void rejectsDishFromAnotherMerchant() {
        Dish other = dish(11L, "其他商家菜品", "12.50", 20, DishStatus.ON_SALE);
        assertThatThrownBy(() -> orderService.createOrder(request("student-1", item(other, 1))))
                .isInstanceOf(OrderConflictException.class).hasMessageContaining("不属于请求商家");
        assertNoOrderAndUnchangedStock(other, 20);
    }

    @Test
    void rejectsOffSaleDishWithoutChangingOtherStock() {
        Dish valid = dish(10L, "鸡腿饭", "12.50", 20, DishStatus.ON_SALE);
        Dish offSale = dish(10L, "下架菜品", "2.00", 5, DishStatus.OFF_SALE);
        assertThatThrownBy(() -> orderService.createOrder(request("student-1", item(valid, 2), item(offSale, 1))))
                .isInstanceOf(OrderConflictException.class).hasMessageContaining("已下架");
        assertNoOrderAndUnchangedStock(valid, 20);
        assertThat(dishRepository.findById(offSale.getId()).orElseThrow().getStock()).isEqualTo(5);
    }

    @Test
    void checksStockAgainstMergedQuantity() {
        Dish dish = dish(10L, "鸡腿饭", "12.50", 3, DishStatus.ON_SALE);
        assertThatThrownBy(() -> orderService.createOrder(request("student-1", item(dish, 2), item(dish, 2))))
                .isInstanceOf(OrderConflictException.class).hasMessageContaining("库存不足");
        assertNoOrderAndUnchangedStock(dish, 3);
    }

    @Test
    void rejectsMergedQuantityOverflow() {
        Dish dish = dish(10L, "鸡腿饭", "1.00", Integer.MAX_VALUE, DishStatus.ON_SALE);
        assertThatThrownBy(() -> orderService.createOrder(request("student-1",
                item(dish, Integer.MAX_VALUE), item(dish, 1))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("数量超出");
        assertNoOrderAndUnchangedStock(dish, Integer.MAX_VALUE);
    }

    /** 改名改价后重新查询订单，确认详情仍然使用创建时的历史快照。 */
    @Test
    void historicalSnapshotsSurviveDishChanges() {
        Dish dish = dish(10L, "鸡腿饭", "12.50", 20, DishStatus.ON_SALE);
        OrderDtos.Response response = orderService.createOrder(request("student-1", item(dish, 2)));
        Dish updated = dishRepository.findById(dish.getId()).orElseThrow();
        updated.setName("新版鸡腿饭");
        updated.setPrice(new BigDecimal("18.00"));
        dishRepository.saveAndFlush(updated);

        OrderDtos.DetailResponse detail = orderService.getOrder(response.orderNo());
        assertThat(detail.totalAmount()).isEqualByComparingTo("25.00");
        assertThat(detail.items().getFirst().dishName()).isEqualTo("鸡腿饭");
        assertThat(detail.items().getFirst().unitPrice()).isEqualByComparingTo("12.50");
        assertThat(detail.items().getFirst().subtotal()).isEqualByComparingTo("25.00");
    }

    @Test
    void listsOnlyRequestedStudentsOrdersInDocumentedOrder() {
        Dish dish = dish(10L, "鸡腿饭", "12.50", 20, DishStatus.ON_SALE);
        String first = orderService.createOrder(request("student-1", item(dish, 1))).orderNo();
        String second = orderService.createOrder(request("student-1", item(dish, 2))).orderNo();
        String newest = orderService.createOrder(request("student-1", item(dish, 1))).orderNo();
        orderService.createOrder(request("student-2", item(dish, 1)));
        LocalDateTime time = LocalDateTime.of(2026, 10, 24, 12, 0);
        for (String orderNo : List.of(first, second, newest)) {
            Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
            order.setCreatedAt(orderNo.equals(newest) ? time.plusMinutes(1) : time);
            orderRepository.saveAndFlush(order);
        }

        assertThat(orderService.getOrders("student-1")).extracting(OrderDtos.SummaryResponse::orderNo)
                .containsExactly(newest, second, first);
        assertThat(orderService.getOrders("student-1")).allSatisfy(summary -> {
            assertThat(summary.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(summary.totalAmount()).isPositive();
            assertThat(summary.createdAt()).isNotNull();
        });
        assertThat(orderService.getOrders("unknown-student")).isEmpty();
    }

    @Test
    void missingOrderReportsNotFound() {
        assertThatThrownBy(() -> orderService.getOrder(UUID.randomUUID().toString()))
                .isInstanceOf(ResourceNotFoundException.class).hasMessageContaining("订单不存在");
    }

    @Test
    void listsOnlyOnSaleDishesForTheRequestedMerchantInIdOrder() {
        Dish first = dish(10L, "鸡腿饭", "12.50", 20, DishStatus.ON_SALE);
        Dish soldOut = dish(10L, "售罄菜品", "3.00", 0, DishStatus.ON_SALE);
        dish(10L, "下架菜品", "3.00", 20, DishStatus.OFF_SALE);
        dish(11L, "其他商家菜品", "3.00", 20, DishStatus.ON_SALE);

        List<DishDtos.Response> dishes = dishService.getDishes(10L);
        assertThat(dishes).extracting(DishDtos.Response::id).containsExactly(first.getId(), soldOut.getId());
        assertThat(dishes.getFirst().price()).isEqualByComparingTo("12.50");
        assertThat(dishes.getLast().stock()).isZero();
        assertThat(dishService.getDishes(Long.MAX_VALUE)).isEmpty();
    }

    /**
     * 测试方法不启用事务：在服务事务中真正 flush 三表变更后制造异常，
     * 服务调用结束后另行查询数据库，确认已写入的订单、明细和所有库存修改全部回滚。
     */
    @Test
    void rollsBackOrderItemsAndEveryStockChangeAfterDatabaseWrites() {
        Dish first = dish(10L, "鸡腿饭", "12.50", 20, DishStatus.ON_SALE);
        Dish second = dish(10L, "饮料", "3.00", 10, DishStatus.ON_SALE);
        AtomicBoolean flushed = new AtomicBoolean();
        doAnswer(invocation -> {
            orderItemRepository.saveAllAndFlush(invocation.getArgument(0));
            assertThat(jdbcTemplate.queryForObject("select count(*) from orders", Long.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("select count(*) from order_items", Long.class)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("select stock from dishes where id = ?",
                    Integer.class, first.getId())).isEqualTo(18);
            assertThat(jdbcTemplate.queryForObject("select stock from dishes where id = ?",
                    Integer.class, second.getId())).isEqualTo(7);
            flushed.set(true);
            throw new DataIntegrityViolationException("模拟数据库写入后失败");
        }).when(orderItemRepository).saveAll(anyList());

        assertThatThrownBy(() -> orderService.createOrder(request("student-1", item(first, 2), item(second, 3))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(flushed).isTrue();
        assertNoOrderAndUnchangedStock(first, 20);
        assertThat(dishRepository.findById(second.getId()).orElseThrow().getStock()).isEqualTo(10);
    }

    private void assertNoOrderAndUnchangedStock(Dish dish, int stock) {
        assertThat(orderRepository.count()).isZero();
        assertThat(orderItemRepository.count()).isZero();
        assertThat(dishRepository.findById(dish.getId()).orElseThrow().getStock()).isEqualTo(stock);
    }

    private Dish dish(Long merchantId, String name, String price, int stock, DishStatus status) {
        Dish dish = new Dish();
        dish.setMerchantId(merchantId);
        dish.setName(name);
        dish.setCategory("主食");
        dish.setPrice(new BigDecimal(price));
        dish.setStock(stock);
        dish.setStatus(status);
        return dishRepository.saveAndFlush(dish);
    }

    private OrderDtos.ItemRequest item(Dish dish, int quantity) {
        return new OrderDtos.ItemRequest(dish.getId(), quantity);
    }

    private OrderDtos.Request request(String userId, OrderDtos.ItemRequest... items) {
        return new OrderDtos.Request(userId, 10L, List.of(items), "学生宿舍 1 幢", "少辣");
    }
}
