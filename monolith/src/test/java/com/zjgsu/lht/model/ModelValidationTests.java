package com.zjgsu.lht.model;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.zjgsu.lht.model.ModelFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class ModelValidationTests {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    /**
     * 验证字段完整且取值合法的菜品、订单和订单明细均能通过 Bean Validation 校验
     */
    @Test
    void validModelsPassValidation() {
        assertThat(validator.validate(dish())).isEmpty();
        assertThat(validator.validate(order())).isEmpty();
        assertThat(validator.validate(item(1L, 1L))).isEmpty();
    }

    /**
     * 验证新订单仅默认设置待支付状态，订单号、创建时间和总金额仍需由 Service 设置
     */
    @Test
    void orderDefaultsOnlyItsStatus() {
        Order order = new Order();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.getOrderNo()).isNull();
        assertThat(order.getCreatedAt()).isNull();
        assertThat(order.getTotalAmount()).isNull();
    }

    /**
     * 验证约束能够拒绝非法字段值
     */
    @ParameterizedTest(name = "{index}: {0}.{1} rejects {2}")
    @MethodSource("invalidValues")
    void rejectsInvalidPropertyValues(Class<?> entityType, String property, Object value) {
        assertThat(validator.validateValue(entityType, property, value)).isNotEmpty();
    }

    static Stream<Arguments> invalidValues() {
        List<Arguments> cases = new ArrayList<>();
        required(cases, Dish.class, "merchantId", "name", "price", "stock", "status");
        required(cases, Order.class, "orderNo", "userId", "merchantId", "status",
                "totalAmount", "deliveryAddress", "createdAt");
        required(cases, OrderItem.class, "orderId", "dishId", "dishName", "unitPrice",
                "quantity", "subtotal");
        blank(cases, Dish.class, "name");
        blank(cases, Order.class, "orderNo", "userId", "deliveryAddress");
        blank(cases, OrderItem.class, "dishName");
        cases.add(Arguments.of(Dish.class, "name", "菜".repeat(101)));
        cases.add(Arguments.of(Dish.class, "category", "类".repeat(65)));
        cases.add(Arguments.of(Order.class, "orderNo", "a".repeat(37)));
        cases.add(Arguments.of(Order.class, "orderNo", "a".repeat(36)));
        cases.add(Arguments.of(Order.class, "orderNo", "00000000-0000-0000-0000-00000000000g"));
        cases.add(Arguments.of(Order.class, "userId", "u".repeat(65)));
        cases.add(Arguments.of(Order.class, "deliveryAddress", "址".repeat(256)));
        cases.add(Arguments.of(Order.class, "remark", "注".repeat(501)));
        cases.add(Arguments.of(OrderItem.class, "dishName", "菜".repeat(101)));
        money(cases, Dish.class, "price");
        money(cases, Order.class, "totalAmount");
        money(cases, OrderItem.class, "unitPrice", "subtotal");
        cases.add(Arguments.of(Dish.class, "stock", -1));
        cases.add(Arguments.of(OrderItem.class, "quantity", 0));
        cases.add(Arguments.of(OrderItem.class, "quantity", -1));
        return cases.stream();
    }

    /**
     * 验证字段长度上限、可选空值、零金额、最大合法金额及正数数量等合法边界值能够通过校验
     */
    @ParameterizedTest(name = "{index}: {0}.{1} accepts {2}")
    @MethodSource("validBoundaryValues")
    void acceptsValidBoundaryValues(Class<?> entityType, String property, Object value) {
        assertThat(validator.validateValue(entityType, property, value)).isEmpty();
    }

    static Stream<Arguments> validBoundaryValues() {
        List<Arguments> cases = new ArrayList<>(List.of(
                Arguments.of(Dish.class, "name", "菜".repeat(100)),
                Arguments.of(Dish.class, "category", "类".repeat(64)),
                Arguments.of(Dish.class, "category", null),
                Arguments.of(Dish.class, "stock", 0),
                Arguments.of(Order.class, "orderNo", "ABCDEF01-2345-6789-ABCD-EF0123456789"),
                Arguments.of(Order.class, "userId", "u".repeat(64)),
                Arguments.of(Order.class, "deliveryAddress", "址".repeat(255)),
                Arguments.of(Order.class, "remark", "注".repeat(500)),
                Arguments.of(Order.class, "remark", null),
                Arguments.of(OrderItem.class, "dishName", "菜".repeat(100)),
                Arguments.of(OrderItem.class, "quantity", 1)
        ));
        for (BigDecimal amount : List.of(BigDecimal.ZERO, new BigDecimal("99999999999999999.99"))) {
            cases.add(Arguments.of(Dish.class, "price", amount));
            cases.add(Arguments.of(Order.class, "totalAmount", amount));
            cases.add(Arguments.of(OrderItem.class, "unitPrice", amount));
            cases.add(Arguments.of(OrderItem.class, "subtotal", amount));
        }
        return cases.stream();
    }

    private static void required(List<Arguments> cases, Class<?> entityType, String... properties) {
        for (String property : properties) {
            cases.add(Arguments.of(entityType, property, null));
        }
    }

    private static void blank(List<Arguments> cases, Class<?> entityType, String... properties) {
        for (String property : properties) {
            cases.add(Arguments.of(entityType, property, ""));
            cases.add(Arguments.of(entityType, property, " \t\n"));
        }
    }

    private static void money(List<Arguments> cases, Class<?> entityType, String... properties) {
        for (String property : properties) {
            cases.add(Arguments.of(entityType, property, new BigDecimal("-0.01")));
            cases.add(Arguments.of(entityType, property, new BigDecimal("1.001")));
            cases.add(Arguments.of(entityType, property, new BigDecimal("100000000000000000.00")));
        }
    }
}
