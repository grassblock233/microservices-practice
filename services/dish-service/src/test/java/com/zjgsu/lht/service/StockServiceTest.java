package com.zjgsu.lht.service;

import com.zjgsu.lht.dto.StockDtos.Item;
import com.zjgsu.lht.dto.StockDtos.Request;
import com.zjgsu.lht.dto.StockDtos.Snapshot;
import com.zjgsu.lht.exception.ApiException;
import com.zjgsu.lht.model.Dish;
import com.zjgsu.lht.model.DishStatus;
import com.zjgsu.lht.repository.DishRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class StockServiceTest {
    @Autowired StockService stock;
    @Autowired DishRepository dishes;
    Long first, second, offSale, otherMerchant;

    @BeforeEach
    void setup() {
        dishes.deleteAll();
        first = seed(10, "鸡腿饭", DishStatus.ON_SALE);
        second = seed(10, "牛肉面", DishStatus.ON_SALE);
        offSale = seed(10, "下架菜品", DishStatus.OFF_SALE);
        otherMerchant = seed(20, "其他商家菜品", DishStatus.ON_SALE);
    }

    private Long seed(long merchant, String name, DishStatus status) {
        var dish = new Dish();
        dish.setMerchantId(merchant);
        dish.setName(name);
        dish.setPrice(new BigDecimal("12.50"));
        dish.setStock(10);
        dish.setStatus(status);
        return dishes.saveAndFlush(dish).getId();
    }

    private int quantity(long id) {
        return dishes.findById(id).orElseThrow().getStock();
    }

    @Test
    void mergesQuantitiesAndReturnsSnapshotsInDishIdOrder() {
        var request = new Request(10L, List.of(new Item(second, 2), new Item(first, 1), new Item(first, 2)));
        assertEquals(List.of(new Snapshot(first, "鸡腿饭", new BigDecimal("12.50"), 3),
                new Snapshot(second, "牛肉面", new BigDecimal("12.50"), 2)), stock.deduct(request));
        assertEquals(7, quantity(first));
        assertEquals(8, quantity(second));

        // Each new request deducts stock, even for an identical body.
        stock.deduct(request);
        assertEquals(4, quantity(first));
        assertEquals(6, quantity(second));
    }

    @ParameterizedTest
    @CsvSource({"DISH_NOT_FOUND, 404", "MERCHANT_MISMATCH, 409", "DISH_OFF_SALE, 409", "INSUFFICIENT_STOCK, 409"})
    void businessRejectionRollsBackEntireDeduction(String code, int status) {
        var invalid = switch (code) {
            case "DISH_NOT_FOUND" -> new Item(Long.MAX_VALUE, 1);
            case "MERCHANT_MISMATCH" -> new Item(otherMerchant, 1);
            case "DISH_OFF_SALE" -> new Item(offSale, 1);
            default -> new Item(second, 11);
        };
        var error = assertThrows(ApiException.class,
                () -> stock.deduct(new Request(10L, List.of(new Item(first, 1), invalid))));
        assertEquals(code, error.code);
        assertEquals(status, error.status);
        for (var id : List.of(first, second, offSale, otherMerchant)) assertEquals(10, quantity(id));
    }

    @Test
    void concurrentDeductionsCannotOversell() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<String> deduct = () -> {
            ready.countDown();
            assertTrue(start.await(5, TimeUnit.SECONDS));
            try {
                stock.deduct(new Request(10L, List.of(new Item(first, 7))));
                return "OK";
            } catch (ApiException error) {
                return error.code;
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(deduct);
            var b = executor.submit(deduct);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(List.of("INSUFFICIENT_STOCK", "OK"),
                    List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)).stream().sorted().toList());
        }
        assertEquals(3, quantity(first));
    }
}
