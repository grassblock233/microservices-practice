package com.zjgsu.lht.service;

import com.zjgsu.lht.dto.OrderDtos;
import com.zjgsu.lht.model.Dish;
import com.zjgsu.lht.model.DishStatus;
import com.zjgsu.lht.repository.DishRepository;
import com.zjgsu.lht.repository.OrderItemRepository;
import com.zjgsu.lht.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.zjgsu.lht.support.TestData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/**
 * 验证真实写入后失败的订单事务会撤销新增订单、明细及库存变更，同时保留既有数据。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderTransactionTests {

    @Autowired
    OrderService orderService;
    @Autowired
    DishRepository dishes;
    @Autowired
    OrderRepository orders;
    @MockitoSpyBean
    OrderItemRepository items;
    @Autowired
    EntityManager entityManager;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate transactions;

    private Dish rice;
    private Dish tea;

    /**
     * 在独立事务中提交两种菜品及已有订单明细，作为故障发生前的数据库基线。
     */
    @BeforeEach
    void prepareCommittedBaseline() {
        transactions.executeWithoutResult(status -> {
            clearBusinessData();
            rice = dishes.save(dish(10L, "鸡腿饭", "12.50", 10, DishStatus.ON_SALE));
            tea = dishes.save(dish(10L, "柠檬茶", "5.00", 20, DishStatus.ON_SALE));
            var existingOrder = orders.save(order("existing-student", LocalDateTime.of(2026, 10, 24, 12, 30)));
            items.save(item(existingOrder, rice, 1));
        });
    }

    /**
     * 显式清理测试已提交的数据，避免事务回滚测试影响其他用例。
     */
    @AfterEach
    void cleanUpCommittedData() {
        transactions.executeWithoutResult(status -> clearBusinessData());
    }

    /**
     * 先确认订单、明细和库存 SQL 已执行，再注入异常，最后在新事务中验证整单回滚。
     */
    @Test
    void rollsBackOrderItemsAndEveryStockChangeAfterActualDatabaseWrites() {
        AtomicBoolean observedDatabaseWrites = new AtomicBoolean();
        SimulatedWriteFailure failure = new SimulatedWriteFailure();
        doAnswer(invocation -> {
            mockingDetails(items).getMockCreationSettings().getDefaultAnswer().answer(invocation);
            entityManager.flush();
            assertThat(jdbc.queryForObject("select count(*) from orders", Long.class)).isEqualTo(2L);
            assertThat(jdbc.queryForObject("select count(*) from order_items", Long.class)).isEqualTo(3L);
            assertThat(stockInDatabase(rice.getId())).isEqualTo(8);
            assertThat(stockInDatabase(tea.getId())).isEqualTo(17);
            observedDatabaseWrites.set(true);
            throw failure;
        }).when(items).saveAll(any());

        // 本类不加 @Transactional，让 Service 自己结束事务，避免测试事务的自动回滚造成假通过。
        assertThatThrownBy(() -> orderService.createOrder(request(
                new OrderDtos.ItemRequest(rice.getId(), 2), new OrderDtos.ItemRequest(tea.getId(), 3))))
                .isSameAs(failure);
        assertThat(observedDatabaseWrites).isTrue();

        // 在失败事务之外重新查询；保留已有订单，新增订单、明细和两种库存变更均应撤销。
        transactions.executeWithoutResult(status -> {
            assertThat(orders.count()).isEqualTo(1);
            assertThat(items.count()).isEqualTo(1);
            assertThat(orders.findByUserIdOrderByCreatedAtDescIdDesc("student-1")).isEmpty();
            assertThat(orders.findByUserIdOrderByCreatedAtDescIdDesc("existing-student")).hasSize(1);
            assertThat(stockInDatabase(rice.getId())).isEqualTo(10);
            assertThat(stockInDatabase(tea.getId())).isEqualTo(20);
        });
    }

    /**
     * 通过 JDBC 读取指定菜品的实际库存，避免一级缓存影响写入与回滚的验证。
     */
    private Integer stockInDatabase(Long dishId) {
        return jdbc.queryForObject("select stock from dishes where id = ?", Integer.class, dishId);
    }

    /**
     * 按明细、订单、菜品的依赖顺序清理业务数据，供事务测试准备和收尾使用。
     */
    private void clearBusinessData() {
        items.deleteAllInBatch();
        orders.deleteAllInBatch();
        dishes.deleteAllInBatch();
    }

    /**
     * 标记故障注入点抛出的运行时异常，用于确认异常传播并触发事务回滚。
     */
    private static class SimulatedWriteFailure extends RuntimeException {
    }
}
