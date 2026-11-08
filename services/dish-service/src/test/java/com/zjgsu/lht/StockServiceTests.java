package com.zjgsu.lht;

import com.zjgsu.lht.dto.StockDtos.Item;
import com.zjgsu.lht.dto.StockDtos.Operation;
import com.zjgsu.lht.dto.StockDtos.Request;
import com.zjgsu.lht.exception.ApiException;
import com.zjgsu.lht.model.Dish;
import com.zjgsu.lht.model.DishStatus;
import com.zjgsu.lht.repository.DishRepository;
import com.zjgsu.lht.repository.StockOperationItemRepository;
import com.zjgsu.lht.repository.StockOperationRepository;
import com.zjgsu.lht.service.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StockServiceTests {
    @Autowired
    StockService stock;
    @Autowired
    DishRepository dishes;
    @MockitoSpyBean
    StockOperationRepository operations;
    @Autowired
    PlatformTransactionManager manager;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StockOperationItemRepository items;
    @Autowired
    MockMvc mvc;
    Long first, second;

    @BeforeEach
    void setup() {
        items.deleteAll();
        operations.deleteAll();
        dishes.deleteAll();
        first = seed(10, "鸡腿饭", 10, DishStatus.ON_SALE);
        second = seed(10, "牛肉面", 10, DishStatus.ON_SALE);
    }

    Long seed(long merchant, String name, int quantity, DishStatus status) {
        var d = new Dish();
        d.setMerchantId(merchant);
        d.setName(name);
        d.setPrice(new BigDecimal("12.50"));
        d.setStock(quantity);
        d.setStatus(status);
        return dishes.saveAndFlush(d).getId();
    }

    Request request(long id, int q) {
        return new Request(10L, List.of(new Item(id, q)));
    }

    String id() {
        return UUID.randomUUID().toString();
    }

    int quantity(Long id) {
        return dishes.findById(id).orElseThrow().getStock();
    }

    void code(String code, Runnable action) {
        assertEquals(code, assertThrows(ApiException.class, action::run).code);
    }

    @Test
    void mergesAndReturnsOriginalSnapshotsForRepeatedDeduction() {
        var op = id();
        var r = new Request(10L, List.of(new Item(first, 1), new Item(first, 2)));
        var a = stock.deduct(op, r);
        assertTrue(a.created());
        assertEquals(7, quantity(first));
        var dish = dishes.findById(first).orElseThrow();
        dish.setPrice(new BigDecimal("99.00"));
        dishes.saveAndFlush(dish);
        var repeated = stock.deduct(op, request(first, 3));
        assertFalse(repeated.created());
        assertEquals(a.operation(), repeated.operation());
        assertEquals(7, quantity(first));
        assertEquals(1, operations.count());
        assertEquals(1, items.count());
    }

    @Test
    void failedBatchRollsBackAllItemsAndOperation() {
        var r = new Request(10L, List.of(new Item(first, 2), new Item(second, 11)));
        code("INSUFFICIENT_STOCK", () -> stock.deduct(id(), r));
        assertEquals(10, quantity(first));
        assertEquals(10, quantity(second));
        assertEquals(0, operations.count());
        assertEquals(0, items.count());
    }

    @Test
    void rejectsMissingOffSaleAndWrongMerchant() {
        code("DISH_NOT_FOUND", () -> stock.validate(request(Long.MAX_VALUE, 1)));
        var off = seed(10, "下架", 10, DishStatus.OFF_SALE);
        code("DISH_OFF_SALE", () -> stock.deduct(id(), request(off, 1)));
        var foreign = seed(20, "其他商家", 10, DishStatus.ON_SALE);
        code("MERCHANT_MISMATCH", () -> stock.deduct(id(), request(foreign, 1)));
        assertEquals(0, operations.count());
    }

    @Test
    void differentContentCannotReuseAnOperationNumber() {
        var op = id();
        stock.deduct(op, request(first, 1));
        code("OPERATION_CONFLICT", () -> stock.deduct(op, request(first, 2)));
        assertEquals(9, quantity(first));
    }

    @Test
    void releaseIsIdempotent() {
        var op = id();
        stock.deduct(op, request(first, 3));
        assertEquals("RELEASED", stock.release(op).status());
        stock.release(op);
        assertEquals(10, quantity(first));
        code("OPERATION_RELEASED", () -> stock.deduct(op, request(first, 3)));
    }

    @Test
    void releaseBeforeDeductCreatesPersistentTombstone() {
        var op = id();
        stock.release(op);
        stock.release(op);
        code("OPERATION_RELEASED", () -> stock.deduct(op, request(first, 1)));
        assertEquals("RELEASED", stock.get(op).status());
        assertEquals(10, quantity(first));
        assertEquals(0, items.count());
    }

    @Test
    void validationDoesNotReserveStockAndDeductRechecks() {
        var r = request(first, 7);
        stock.validate(r);
        assertEquals(10, quantity(first));
        stock.deduct(id(), request(first, 5));
        code("INSUFFICIENT_STOCK", () -> stock.deduct(id(), r));
        assertEquals(5, quantity(first));
    }

    @Test
    void rejectsOverflowAndInvalidQuantities() {
        code("INVALID_REQUEST", () -> stock.validate(new Request(10L, List.of(new Item(first, Integer.MAX_VALUE), new Item(first, 1)))));
        code("INVALID_REQUEST", () -> stock.validate(request(first, 0)));
        code("INVALID_REQUEST", () -> stock.validate(new Request(10L, List.of())));
    }

    @Test
    void releaseCommittedBetweenLookupAndClaimCannotBeOverwritten() {
        var op = id();
        var firstLookup = new AtomicBoolean(true);
        var independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(invocation -> {
            if (firstLookup.getAndSet(false)) {
                independent.executeWithoutResult(s -> jdbc.update(
                        "insert into stock_operations (order_no,status,updated_at) values (?,'RELEASED',CURRENT_TIMESTAMP)", op));
                return Optional.empty(); // Return the stale, pre-commit lookup result.
            }
            return operations.findById(op);
        }).when(operations).lockByOrderNo(op);
        code("OPERATION_RELEASED", () -> stock.deduct(op, request(first, 3)));
        assertEquals("RELEASED", stock.get(op).status());
        assertEquals(10, quantity(first));
        assertEquals(0, items.count());
    }

    @Test
    void concurrentOrdersNeverOversell() throws Exception {
        Callable<Boolean> work = () -> {
            try {
                stock.deduct(id(), request(first, 7));
                return true;
            } catch (ApiException e) {
                assertEquals("INSUFFICIENT_STOCK", e.code);
                return false;
            }
        };
        var results = concurrently(work, work);
        assertNotEquals(results.getFirst(), results.getLast());
        assertEquals(3, quantity(first));
        assertEquals(1, operations.count());
    }

    @Test
    void concurrentSameOperationDeductsOnce() throws Exception {
        var op = id();
        Callable<Operation> work = () -> stock.deduct(op, request(first, 3)).operation();
        var results = concurrently(work, work);
        assertEquals(results.getFirst(), results.getLast());
        assertEquals(7, quantity(first));
        assertEquals(1, items.count());
    }

    @Test
    void concurrentReleaseAndDeductEndReleasedWithFullStock() throws Exception {
        var op = id();
        concurrently(() -> {
            try {
                stock.deduct(op, request(first, 3));
            } catch (ApiException e) {
                assertEquals("OPERATION_RELEASED", e.code);
            }
            return true;
        }, () -> {
            stock.release(op);
            return true;
        });
        assertEquals("RELEASED", stock.get(op).status());
        assertEquals(10, quantity(first));
    }

    private <T> List<T> concurrently(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(first, second).stream().map(work -> pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS), "Concurrent start timed out");
                return work.call();
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Workers did not become ready");
            start.countDown();
            return List.of(tasks.getFirst().get(15, TimeUnit.SECONDS),
                    tasks.getLast().get(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsMalformedAndInvalidBodies() throws Exception {
        for (String body : new String[]{"{", "{}", "{\"merchantId\":10,\"items\":[]}",
                "{\"merchantId\":10,\"items\":[{\"dishId\":1,\"quantity\":1.2}]}"})
            mvc.perform(post("/internal/dishes/validate").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertEquals(0, operations.count());
        assertEquals(10, quantity(first));
    }

    @Test
    void healthChecksOwnDatabase() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
