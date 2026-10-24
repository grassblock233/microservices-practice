package com.zjgsu.lht.repository;

import com.zjgsu.lht.model.Dish;
import com.zjgsu.lht.model.DishStatus;
import com.zjgsu.lht.model.Order;
import com.zjgsu.lht.model.OrderItem;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.zjgsu.lht.support.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用 H2 验证 Repository 的条件筛选、订单号查找、排序规则和空结果。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class BusinessRepositoryTests {

    @Autowired
    DishRepository dishes;
    @Autowired
    OrderRepository orders;
    @Autowired
    OrderItemRepository items;
    @Autowired
    EntityManager entityManager;

    /**
     * 验证按商家及上架状态筛选菜品，并按 ID 升序返回，包含库存为零的上架菜品。
     */
    @Test
    void filtersDishesByMerchantAndStatusAndSortsById() {
        Dish first = dishes.save(dish(10L, "鸡腿饭", "12.50", 10, DishStatus.ON_SALE));
        dishes.save(dish(20L, "其他商家", "9.00", 10, DishStatus.ON_SALE));
        dishes.save(dish(10L, "已下架", "8.00", 10, DishStatus.OFF_SALE));
        Dish last = dishes.save(dish(10L, "已售罄", "5.00", 0, DishStatus.ON_SALE));
        flushAndClear();

        assertThat(dishes.findByMerchantIdAndStatusOrderByIdAsc(10L, DishStatus.ON_SALE))
                .extracting(Dish::getId).containsExactly(first.getId(), last.getId());
        assertThat(dishes.findByMerchantIdAndStatusOrderByIdAsc(999L, DishStatus.ON_SALE)).isEmpty();
    }

    /**
     * 验证订单仅属于指定学生，先按创建时间倒序，再按 ID 倒序处理同时间订单。
     */
    @Test
    void filtersStudentsOrdersAndBreaksCreationTimeTiesByDescendingId() {
        LocalDateTime time = LocalDateTime.of(2026, 10, 24, 12, 30);
        Order newestTime = orders.save(order("student-1", time.plusHours(1)));
        Order older = orders.save(order("student-1", time.minusHours(1)));
        Order tiedFirst = orders.save(order("student-1", time));
        orders.save(order("student-2", time.plusHours(2)));
        Order tiedLast = orders.save(order("student-1", time));
        flushAndClear();

        assertThat(orders.findByUserIdOrderByCreatedAtDescIdDesc("student-1"))
                .extracting(Order::getOrderNo)
                .containsExactly(newestTime.getOrderNo(), tiedLast.getOrderNo(), tiedFirst.getOrderNo(), older.getOrderNo());
        assertThat(orders.findByUserIdOrderByCreatedAtDescIdDesc("student-without-orders")).isEmpty();
    }

    /**
     * 验证通过对外 UUID 订单号找到对应订单，不存在的订单号返回空 Optional。
     */
    @Test
    void findsOrderByExternalOrderNo() {
        Order saved = orders.save(order("student-1", LocalDateTime.of(2026, 10, 24, 12, 30)));
        flushAndClear();
        assertThat(orders.findByOrderNo(saved.getOrderNo())).get()
                .extracting(Order::getId).isEqualTo(saved.getId());
        assertThat(orders.findByOrderNo(UUID.randomUUID().toString())).isEmpty();
    }

    /**
     * 验证只查询指定订单的明细，按菜品 ID 而非插入顺序排序，且无明细时返回空列表。
     */
    @Test
    void filtersItemsByOrderAndSortsByDishIdInsteadOfInsertionOrder() {
        Dish firstDish = dishes.save(dish(10L, "鸡腿饭", "12.50", 10, DishStatus.ON_SALE));
        Dish lastDish = dishes.save(dish(10L, "柠檬茶", "5.00", 10, DishStatus.ON_SALE));
        Order firstOrder = orders.save(order("student-1", LocalDateTime.of(2026, 10, 24, 12, 30)));
        Order otherOrder = orders.save(order("student-2", LocalDateTime.of(2026, 10, 24, 12, 30)));
        items.saveAll(List.of(item(firstOrder, lastDish, 1), item(otherOrder, firstDish, 1),
                item(firstOrder, firstDish, 1)));
        flushAndClear();

        assertThat(items.findByOrderIdOrderByDishIdAsc(firstOrder.getId()))
                .extracting(OrderItem::getDishId).containsExactly(firstDish.getId(), lastDish.getId());
        assertThat(items.findByOrderIdOrderByDishIdAsc(-1L)).isEmpty();
    }

    /**
     * 将当前持久化变更写入数据库并清空一级缓存，确保查询验证真实数据库行为。
     */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
