package com.zjgsu.lht.model;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.math.BigDecimal;

import static com.zjgsu.lht.model.ModelFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:model-tests;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ModelPersistenceTests {

    @Autowired
    private EntityManager entityManager;

    /**
     * 验证状态列采用 VARCHAR(32)、创建时间保留微秒精度，以及订单号采用 CHAR 类型。
     */
    @Test
    void createsVarcharStatusesAndMicrosecondTimestamp() {
        for (String table : new String[]{"DISHES", "ORDERS"}) {
            assertThat(entityManager.createNativeQuery("""
                    select data_type from information_schema.columns
                    where table_name = :table and column_name = 'STATUS'
                    """, String.class).setParameter("table", table).getSingleResult())
                    .isEqualTo("CHARACTER VARYING");
            assertThat(entityManager.createNativeQuery("""
                    select character_maximum_length from information_schema.columns
                    where table_name = :table and column_name = 'STATUS'
                    """, Long.class).setParameter("table", table).getSingleResult())
                    .isEqualTo(32L);
        }
        assertThat(entityManager.createNativeQuery("""
                select datetime_precision from information_schema.columns
                where table_name = 'ORDERS' and column_name = 'CREATED_AT'
                """, Integer.class).getSingleResult()).isEqualTo(6);
        assertThat(entityManager.createNativeQuery("""
                select data_type from information_schema.columns
                where table_name = 'ORDERS' and column_name = 'ORDER_NO'
                """, String.class).getSingleResult()).isEqualTo("CHARACTER");
    }

    /**
     * 验证数据库文档要求的查询索引已创建，且索引字段的顺序正确。
     */
    @Test
    void createsDocumentedQueryIndexes() {
        assertThat(indexColumns("IDX_DISHES_MERCHANT_STATUS_ID"))
                .containsExactly("MERCHANT_ID", "STATUS", "ID");
        assertThat(indexColumns("IDX_ORDERS_USER_CREATED_ID"))
                .containsExactly("USER_ID", "CREATED_AT", "ID");
        assertThat(indexColumns("IDX_ORDER_ITEMS_DISH_ID"))
                .containsExactly("DISH_ID");
    }

    private java.util.List<String> indexColumns(String indexName) {
        return entityManager.createNativeQuery("""
                select column_name from information_schema.index_columns
                where index_name = :indexName order by ordinal_position
                """, String.class).setParameter("indexName", indexName).getResultList();
    }

    /**
     * 验证三个实体均能生成自增主键、完整保存和读取字段，并将枚举状态存储为字符串。
     */
    @Test
    void persistsAllFieldsWithGeneratedIdsAndStringStatuses() {
        Dish dish = dish();
        Order order = order();
        assertThat(dish.getId()).isNull();
        assertThat(order.getId()).isNull();
        entityManager.persist(dish);
        entityManager.persist(order);
        OrderItem item = item(order.getId(), dish.getId());
        assertThat(item.getId()).isNull();
        entityManager.persist(item);
        entityManager.flush();
        entityManager.clear();

        assertThat(dish.getId()).isPositive();
        assertThat(order.getId()).isPositive();
        assertThat(item.getId()).isPositive();
        assertThat(entityManager.find(Dish.class, dish.getId()))
                .usingRecursiveComparison().isEqualTo(dish);
        assertThat(entityManager.find(Order.class, order.getId()))
                .usingRecursiveComparison().isEqualTo(order);
        assertThat(entityManager.find(OrderItem.class, item.getId()))
                .usingRecursiveComparison().isEqualTo(item);
        assertThat(entityManager.createNativeQuery("select status from dishes where id = :id", String.class)
                .setParameter("id", dish.getId()).getSingleResult()).isEqualTo("ON_SALE");
        assertThat(entityManager.createNativeQuery("select status from orders where id = :id", String.class)
                .setParameter("id", order.getId()).getSingleResult()).isEqualTo("PENDING_PAYMENT");
    }

    /**
     * 验证可选字段允许为空，零金额、零库存及下架状态能够正确保存和读取。
     */
    @Test
    void acceptsOptionalNullFieldsAndZeroAmountsAndStock() {
        Dish dish = dish();
        dish.setCategory(null);
        dish.setPrice(BigDecimal.ZERO);
        dish.setStock(0);
        dish.setStatus(DishStatus.OFF_SALE);
        Order order = order();
        order.setRemark(null);
        order.setTotalAmount(BigDecimal.ZERO);
        entityManager.persist(dish);
        entityManager.persist(order);
        OrderItem item = item(order.getId(), dish.getId());
        item.setUnitPrice(BigDecimal.ZERO);
        item.setSubtotal(BigDecimal.ZERO);
        entityManager.persist(item);
        entityManager.flush();
        entityManager.clear();

        Dish storedDish = entityManager.find(Dish.class, dish.getId());
        assertThat(storedDish.getCategory()).isNull();
        assertThat(storedDish.getStock()).isZero();
        assertThat(storedDish.getPrice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(storedDish.getStatus()).isEqualTo(DishStatus.OFF_SALE);
        Order storedOrder = entityManager.find(Order.class, order.getId());
        assertThat(storedOrder.getRemark()).isNull();
        assertThat(storedOrder.getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        OrderItem storedItem = entityManager.find(OrderItem.class, item.getId());
        assertThat(storedItem.getUnitPrice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(storedItem.getSubtotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * 验证所有金额字段在保存和读取 DECIMAL(19,2) 的最大合法值时不会丢失精度。
     */
    @Test
    void preservesFullDecimalPrecision() {
        BigDecimal maximum = new BigDecimal("99999999999999999.99");
        Dish dish = dish();
        dish.setPrice(maximum);
        Order order = order();
        order.setTotalAmount(maximum);
        entityManager.persist(dish);
        entityManager.persist(order);
        OrderItem item = item(order.getId(), dish.getId());
        item.setUnitPrice(maximum);
        item.setQuantity(1);
        item.setSubtotal(maximum);
        entityManager.persist(item);
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(Dish.class, dish.getId()).getPrice()).isEqualTo(maximum);
        assertThat(entityManager.find(Order.class, order.getId()).getTotalAmount()).isEqualTo(maximum);
        OrderItem storedItem = entityManager.find(OrderItem.class, item.getId());
        assertThat(storedItem.getUnitPrice()).isEqualTo(maximum);
        assertThat(storedItem.getSubtotal()).isEqualTo(maximum);
    }

    /**
     * 验证订单号唯一约束生效，重复订单号在持久化时会触发数据库约束异常。
     */
    @Test
    void rejectsDuplicateOrderNumbers() {
        Order first = order();
        entityManager.persist(first);
        entityManager.flush();
        Order duplicate = order();
        duplicate.setOrderNo(first.getOrderNo());

        assertThatThrownBy(() -> {
            entityManager.persist(duplicate);
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
                .hasRootCauseInstanceOf(java.sql.SQLIntegrityConstraintViolationException.class);
    }

    /**
     * 验证订单与菜品的联合唯一约束生效，同一订单不能重复保存同一种菜品的明细。
     */
    @Test
    void rejectsDuplicateDishWithinAnOrder() {
        Dish dish = dish();
        Order order = order();
        entityManager.persist(dish);
        entityManager.persist(order);
        entityManager.persist(item(order.getId(), dish.getId()));
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.persist(item(order.getId(), dish.getId()));
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
                .hasRootCauseInstanceOf(java.sql.SQLIntegrityConstraintViolationException.class);
    }

    /**
     * 验证同一订单可包含不同菜品，且同一种菜品可以出现在不同订单中。
     */
    @Test
    void allowsDifferentDishesInOneOrderAndTheSameDishInDifferentOrders() {
        Dish firstDish = dish();
        Dish secondDish = dish();
        Order firstOrder = order();
        Order secondOrder = order();
        entityManager.persist(firstDish);
        entityManager.persist(secondDish);
        entityManager.persist(firstOrder);
        entityManager.persist(secondOrder);
        entityManager.persist(item(firstOrder.getId(), firstDish.getId()));
        entityManager.persist(item(firstOrder.getId(), secondDish.getId()));
        entityManager.persist(item(secondOrder.getId(), firstDish.getId()));
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.createQuery("select count(i) from OrderItem i", Long.class)
                .getSingleResult()).isEqualTo(3L);
    }

    /**
     * 验证菜品改名、改价后，历史订单明细的名称、单价、数量、小计及订单总金额保持不变。
     */
    @Test
    void dishChangesDoNotChangeHistoricalSnapshots() {
        Dish dish = dish();
        Order order = order();
        entityManager.persist(dish);
        entityManager.persist(order);
        OrderItem item = item(order.getId(), dish.getId());
        entityManager.persist(item);
        entityManager.flush();
        entityManager.clear();

        Dish changedDish = entityManager.find(Dish.class, dish.getId());
        changedDish.setName("新版鸡腿饭");
        changedDish.setPrice(new BigDecimal("18.00"));
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(Dish.class, dish.getId()).getPrice())
                .isEqualByComparingTo("18.00");
        OrderItem storedItem = entityManager.find(OrderItem.class, item.getId());
        assertThat(storedItem.getDishName()).isEqualTo("鸡腿饭");
        assertThat(storedItem.getUnitPrice()).isEqualByComparingTo("12.50");
        assertThat(storedItem.getQuantity()).isEqualTo(2);
        assertThat(storedItem.getSubtotal()).isEqualByComparingTo("25.00");
        assertThat(entityManager.find(Order.class, order.getId()).getTotalAmount())
                .isEqualByComparingTo("25.00");
    }

    /**
     * 验证持久化过程会执行 Bean Validation，并拒绝保存库存为负数的菜品。
     */
    @Test
    void persistenceInvokesBeanValidation() {
        Dish dish = dish();
        dish.setStock(-1);

        assertThatThrownBy(() -> {
            entityManager.persist(dish);
            entityManager.flush();
        }).isInstanceOf(ConstraintViolationException.class);
    }
}
