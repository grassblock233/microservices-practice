package com.zjgsu.lht.service;

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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.zjgsu.lht.support.TestData.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 结合真实 Service、Repository 和 H2，验证下单规则、数据持久化、历史快照及查询结果映射。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OrderService.class, DishService.class})
@ActiveProfiles("test")
class OrderServiceTests {

    @Autowired
    OrderService orderService;
    @Autowired
    DishService dishService;
    @Autowired
    DishRepository dishes;
    @Autowired
    OrderRepository orders;
    @Autowired
    OrderItemRepository items;
    @Autowired
    EntityManager entityManager;

    /**
     * 验证多菜品下单生成合法订单号与准确金额，并正确保存订单、明细快照和库存扣减。
     */
    @Test
    void createsMultiDishOrderWithExactAmountsSnapshotsAndStockUpdates() {
        Dish rice = dishes.save(dish(10L, "鸡腿饭", "12.50", 10, DishStatus.ON_SALE));
        Dish tea = dishes.save(dish(10L, "柠檬茶", "0.10", 20, DishStatus.ON_SALE));
        LocalDateTime before = LocalDateTime.now();

        var response = orderService.createOrder(request(
                new OrderDtos.ItemRequest(rice.getId(), 2), new OrderDtos.ItemRequest(tea.getId(), 3)));

        assertThat(UUID.fromString(response.orderNo()).toString()).isEqualTo(response.orderNo());
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        // 使用 0.10 等小数构造金额，按数值比较 BigDecimal，避免 scale 差异和浮点计算干扰。
        assertThat(response.totalAmount()).isEqualByComparingTo("25.30");
        reloadFromDatabase();

        Order saved = orders.findByOrderNo(response.orderNo()).orElseThrow();
        assertThat(orders.count()).isEqualTo(1);
        assertThat(saved.getUserId()).isEqualTo("student-1");
        assertThat(saved.getMerchantId()).isEqualTo(10L);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("25.30");
        assertThat(saved.getDeliveryAddress()).isEqualTo("学生宿舍 1 幢");
        assertThat(saved.getRemark()).isEqualTo("少辣");
        assertThat(saved.getCreatedAt()).isBetween(before.minusSeconds(1), LocalDateTime.now().plusSeconds(1));
        var savedItems = items.findByOrderIdOrderByDishIdAsc(saved.getId());
        assertThat(savedItems).hasSize(2);
        assertThat(savedItems.get(0).getDishId()).isEqualTo(rice.getId());
        assertThat(savedItems.get(0).getDishName()).isEqualTo("鸡腿饭");
        assertThat(savedItems.get(0).getUnitPrice()).isEqualByComparingTo("12.50");
        assertThat(savedItems.get(0).getQuantity()).isEqualTo(2);
        assertThat(savedItems.get(0).getSubtotal()).isEqualByComparingTo("25.00");
        assertThat(savedItems.get(1).getDishId()).isEqualTo(tea.getId());
        assertThat(savedItems.get(1).getDishName()).isEqualTo("柠檬茶");
        assertThat(savedItems.get(1).getUnitPrice()).isEqualByComparingTo("0.10");
        assertThat(savedItems.get(1).getQuantity()).isEqualTo(3);
        assertThat(savedItems.get(1).getSubtotal()).isEqualByComparingTo("0.30");
        assertThat(dishes.findById(rice.getId()).orElseThrow().getStock()).isEqualTo(8);
        assertThat(dishes.findById(tea.getId()).orElseThrow().getStock()).isEqualTo(17);
    }

    /**
     * 验证重复菜品合并为一条明细，且购买数量恰好等于库存时可以成功下单。
     */
    @Test
    void mergesDuplicateDishIntoOneSnapshotAndAllowsBuyingExactStock() {
        Dish rice = dishes.save(dish(10L, "鸡腿饭", "12.50", 5, DishStatus.ON_SALE));
        // 两条记录分别小于库存，合并后恰好为 5，验证只保存一条明细且库存可扣到零。
        var response = orderService.createOrder(request(
                new OrderDtos.ItemRequest(rice.getId(), 2), new OrderDtos.ItemRequest(rice.getId(), 3)));
        reloadFromDatabase();
        var order = orders.findByOrderNo(response.orderNo()).orElseThrow();
        var savedItems = items.findByOrderIdOrderByDishIdAsc(order.getId());
        assertThat(savedItems).singleElement().satisfies(item -> {
            assertThat(item.getQuantity()).isEqualTo(5);
            assertThat(item.getSubtotal()).isEqualByComparingTo("62.50");
        });
        assertThat(response.totalAmount()).isEqualByComparingTo("62.50");
        assertThat(dishes.findById(rice.getId()).orElseThrow().getStock()).isZero();
    }

    /**
     * 验证库存校验使用重复菜品合并后的总数量，合并后超库存时拒绝整单。
     */
    @Test
    void checksStockAgainstMergedQuantity() {
        Dish rice = dishes.save(dish(10L, "鸡腿饭", "12.50", 5, DishStatus.ON_SALE));
        // 单条数量均为 3，看似可以买；合并为 6 后必须整单拒绝。
        assertThatThrownBy(() -> orderService.createOrder(request(
                new OrderDtos.ItemRequest(rice.getId(), 3), new OrderDtos.ItemRequest(rice.getId(), 3))))
                .isInstanceOf(OrderConflictException.class).hasMessageContaining("库存不足");
        assertNoOrderWasWritten(rice, 5);
    }

    /**
     * 验证重复菜品数量相加超出 Integer 范围时抛出请求异常，不写订单或扣库存。
     */
    @Test
    void rejectsMergedQuantityOverflow() {
        Dish rice = dishes.save(dish(10L, "鸡腿饭", "12.50", Integer.MAX_VALUE, DishStatus.ON_SALE));
        assertThatThrownBy(() -> orderService.createOrder(request(
                new OrderDtos.ItemRequest(rice.getId(), Integer.MAX_VALUE),
                new OrderDtos.ItemRequest(rice.getId(), 1))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("超出允许范围");
        assertNoOrderWasWritten(rice, Integer.MAX_VALUE);
    }

    /**
     * 逐一验证不存在、跨商家、下架或库存不足的菜品使整单失败，其他菜品库存保持不变。
     */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "other-merchant", "off-sale", "insufficient-stock"})
    void rejectsEntireOrderWhenAnyDishFailsEligibility(String scenario) {
        Dish valid = dishes.save(dish(10L, "鸡腿饭", "12.50", 10, DishStatus.ON_SALE));
        Dish invalid = dish(10L, "牛肉面", "16.00", 5, DishStatus.ON_SALE);
        String expectedReason = switch (scenario) {
            case "missing" -> "菜品不存在";
            case "other-merchant" -> {
                invalid.setMerchantId(20L);
                yield "不属于请求商家";
            }
            case "off-sale" -> {
                invalid.setStatus(DishStatus.OFF_SALE);
                yield "已下架";
            }
            default -> {
                invalid.setStock(1);
                yield "库存不足";
            }
        };
        dishes.saveAndFlush(invalid);
        Long invalidId = invalid.getId();
        int originalStock = invalid.getStock();
        if (scenario.equals("missing")) {
            dishes.delete(invalid);
            dishes.flush();
        }
        // 合法菜品放在前面，确认后续菜品失败不会提前扣减前一项的库存。
        assertThatThrownBy(() -> orderService.createOrder(request(
                new OrderDtos.ItemRequest(valid.getId(), 2), new OrderDtos.ItemRequest(invalidId, 2))))
                .isInstanceOf(scenario.equals("missing")
                        ? ResourceNotFoundException.class : OrderConflictException.class)
                .hasMessageContaining(expectedReason);
        assertNoOrderWasWritten(valid, 10);
        if (!scenario.equals("missing")) {
            assertThat(dishes.findById(invalidId).orElseThrow().getStock()).isEqualTo(originalStock);
        }
    }

    /**
     * 验证菜品改名或改价后，订单详情仍读取下单时保存的名称、单价和金额快照。
     */
    @Test
    void readsHistoricalSnapshotAfterDishNameAndPriceChange() {
        Dish rice = dishes.save(dish(10L, "鸡腿饭", "12.50", 10, DishStatus.ON_SALE));
        var response = orderService.createOrder(request(new OrderDtos.ItemRequest(rice.getId(), 2)));
        reloadFromDatabase();
        Dish current = dishes.findById(rice.getId()).orElseThrow();
        current.setName("改名后的套餐");
        current.setPrice(new BigDecimal("99.99"));
        reloadFromDatabase();

        var detail = orderService.getOrder(response.orderNo());
        assertThat(detail.orderNo()).isEqualTo(response.orderNo());
        assertThat(detail.userId()).isEqualTo("student-1");
        assertThat(detail.merchantId()).isEqualTo(10L);
        assertThat(detail.deliveryAddress()).isEqualTo("学生宿舍 1 幢");
        assertThat(detail.remark()).isEqualTo("少辣");
        assertThat(detail.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(detail.createdAt()).isNotNull();
        assertThat(detail.totalAmount()).isEqualByComparingTo("25.00");
        assertThat(detail.items()).singleElement().satisfies(item -> {
            assertThat(item.dishId()).isEqualTo(rice.getId());
            assertThat(item.dishName()).isEqualTo("鸡腿饭");
            assertThat(item.unitPrice()).isEqualByComparingTo("12.50");
            assertThat(item.quantity()).isEqualTo(2);
            assertThat(item.subtotal()).isEqualByComparingTo("25.00");
        });
        assertThat(dishes.findById(rice.getId()).orElseThrow().getName()).isEqualTo("改名后的套餐");
    }

    /**
     * 验证 Service 仅返回指定学生的摘要，并保持创建时间及同时间 ID 倒序的查询顺序。
     */
    @Test
    void returnsOnlyRequestedStudentsSummariesInRepositoryOrder() {
        LocalDateTime time = LocalDateTime.of(2026, 10, 24, 12, 30);
        Order older = orders.save(order("student-1", time.minusHours(1)));
        Order newer = orders.save(order("student-1", time));
        Order sameTimeLaterId = orders.save(order("student-1", time));
        orders.save(order("student-2", time.plusHours(1)));
        reloadFromDatabase();

        var summaries = orderService.getOrders("student-1");
        assertThat(summaries).extracting(OrderDtos.SummaryResponse::orderNo)
                .containsExactly(sameTimeLaterId.getOrderNo(), newer.getOrderNo(), older.getOrderNo());
        assertThat(summaries).allSatisfy(summary -> {
            assertThat(summary.totalAmount()).isEqualByComparingTo("12.50");
            assertThat(summary.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(summary.createdAt()).isNotNull();
        });
        assertThat(orderService.getOrders("student-without-orders")).isEmpty();
    }

    /**
     * 验证按不存在的订单号查询时抛出资源不存在异常，并说明对应订单号。
     */
    @Test
    void reportsMissingOrder() {
        String missingOrderNo = UUID.randomUUID().toString();
        assertThatThrownBy(() -> orderService.getOrder(missingOrderNo))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("订单不存在：" + missingOrderNo);
    }

    /**
     * 验证菜品响应映射仅包含指定商家的上架菜品，保留售罄菜品并正确处理空结果。
     */
    @Test
    void mapsOnlyOnSaleMerchantDishesIncludingZeroStockToResponses() {
        Dish rice = dishes.save(dish(10L, "鸡腿饭", "12.50", 10, DishStatus.ON_SALE));
        Dish soldOut = dishes.save(dish(10L, "柠檬茶", "5.00", 0, DishStatus.ON_SALE));
        dishes.save(dish(10L, "下架菜品", "8.00", 10, DishStatus.OFF_SALE));
        dishes.save(dish(20L, "其他商家", "9.00", 10, DishStatus.ON_SALE));
        reloadFromDatabase();

        var responses = dishService.getDishes(10L);
        assertThat(responses).extracting(response -> response.id()).containsExactly(rice.getId(), soldOut.getId());
        assertThat(responses.getFirst().name()).isEqualTo("鸡腿饭");
        assertThat(responses.getFirst().category()).isEqualTo("主食");
        assertThat(responses.getFirst().price()).isEqualByComparingTo("12.50");
        assertThat(responses).allSatisfy(response -> {
            assertThat(response.merchantId()).isEqualTo(10L);
            assertThat(response.status()).isEqualTo(DishStatus.ON_SALE);
        });
        assertThat(responses.getLast().stock()).isZero();
        assertThat(dishService.getDishes(999L)).isEmpty();
    }

    /**
     * 重新读取数据库，统一断言没有生成订单或明细，且指定菜品库存未改变。
     */
    private void assertNoOrderWasWritten(Dish unchangedDish, int stock) {
        reloadFromDatabase();
        assertThat(orders.count()).isZero();
        assertThat(items.count()).isZero();
        assertThat(dishes.findById(unchangedDish.getId()).orElseThrow().getStock()).isEqualTo(stock);
    }

    /**
     * 执行待写入 SQL 并清空一级缓存，使后续断言检查数据库结果而非内存实体。
     */
    private void reloadFromDatabase() {
        // flush 将脏检查产生的 SQL 真正执行，clear 避免一级缓存掩盖持久化错误。
        entityManager.flush();
        entityManager.clear();
    }
}
