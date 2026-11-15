package com.zjgsu.lht;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zjgsu.lht.client.DishFeignClient;
import com.zjgsu.lht.client.UserFeignClient;
import com.zjgsu.lht.dto.OrderDtos;
import com.zjgsu.lht.exception.ApiException;
import com.zjgsu.lht.repository.OrderAttemptRepository;
import com.zjgsu.lht.repository.OrderItemRepository;
import com.zjgsu.lht.repository.OrderRepository;
import com.zjgsu.lht.service.OrderService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Real Feign HTTP + Spring Cloud round-robin + local H2; no Docker/Nacos required.
 */
@SpringBootTest(properties = {
        "spring.cloud.discovery.enabled=true",
        "spring.cloud.discovery.reactive.enabled=false",
        "spring.cloud.openfeign.client.config.default.connectTimeout=500",
        "spring.cloud.openfeign.client.config.default.readTimeout=500"
})
@ActiveProfiles("test")
class FeignClientTests {
    private static final String SNAPSHOT =
            "[{\"dishId\":1,\"dishName\":\"鸡腿饭\",\"unitPrice\":12.50,\"quantity\":3}]";
    private static final ExecutorService HTTP_THREADS = Executors.newVirtualThreadPerTaskExecutor();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger USER_STATUS = new AtomicInteger(200);
    private static final AtomicInteger DISH_STATUS = new AtomicInteger(200);
    private static final AtomicInteger FIRST_HITS = new AtomicInteger();
    private static final AtomicInteger SECOND_HITS = new AtomicInteger();
    private static final AtomicInteger DEDUCTIONS = new AtomicInteger();
    private static final AtomicInteger RELEASES = new AtomicInteger();
    private static final AtomicInteger STOCK = new AtomicInteger();
    private static final Map<String, String> OPERATIONS = new ConcurrentHashMap<>();
    private static volatile boolean malformedUser;
    private static volatile boolean slowDeduction;
    private static volatile boolean failedRelease;
    private static final HttpServer USERS = start(true, new AtomicInteger());
    private static final HttpServer DISH_ONE = start(false, FIRST_HITS);
    private static final HttpServer DISH_TWO = start(false, SECOND_HITS);

    @Autowired
    OrderService orders;
    @Autowired
    OrderRepository repository;
    @Autowired
    OrderItemRepository items;
    @Autowired
    OrderAttemptRepository attempts;
    @Autowired
    UserFeignClient users;
    @Autowired
    DishFeignClient dishes;
    @MockitoBean
    DiscoveryClient discovery;

    @BeforeEach
    void reset() {
        items.deleteAll();
        repository.deleteAll();
        attempts.deleteAll();
        USER_STATUS.set(200);
        DISH_STATUS.set(200);
        FIRST_HITS.set(0);
        SECOND_HITS.set(0);
        DEDUCTIONS.set(0);
        RELEASES.set(0);
        STOCK.set(20);
        OPERATIONS.clear();
        malformedUser = slowDeduction = failedRelease = false;
        when(discovery.getInstances("user-service")).thenReturn(List.of(instance("u1", "user-service", USERS)));
        when(discovery.getInstances("dish-service")).thenReturn(List.of(
                instance("d1", "dish-service", DISH_ONE), instance("d2", "dish-service", DISH_TWO)));
    }

    private static DefaultServiceInstance instance(String id, String name, HttpServer server) {
        return new DefaultServiceInstance(id, name, "127.0.0.1", server.getAddress().getPort(), false);
    }

    private static HttpServer start(boolean user, AtomicInteger hits) {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(HTTP_THREADS);
            server.createContext("/", exchange -> {
                hits.incrementAndGet();
                try (exchange) {
                    handle(exchange, user);
                } catch (IOException ignored) {
                    // The timeout case deliberately closes the connection before the response.
                }
            });
            server.start();
            return server;
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void handle(HttpExchange exchange, boolean user) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (user) {
            if (!exchange.getRequestMethod().equals("GET") || !path.equals("/api/users/student-1")) {
                send(exchange, 400, "{}");
                return;
            }
            send(exchange, USER_STATUS.get(), USER_STATUS.get() == 200
                    ? (malformedUser ? "not-json" : "{\"id\":\"student-1\",\"displayName\":\"同学一\",\"status\":\"ACTIVE\"}")
                    : "{\"code\":\"USER_NOT_FOUND\"}");
            return;
        }
        if (DISH_STATUS.get() != 200) {
            send(exchange, DISH_STATUS.get(), "{\"code\":\"DISH_NOT_FOUND\"}");
            return;
        }
        boolean validate = path.equals("/internal/dishes/validate");
        boolean deduct = path.matches("/internal/stock-operations/[^/]+/deduct");
        boolean release = path.matches("/internal/stock-operations/[^/]+/release");
        if ((validate || deduct) && exchange.getRequestMethod().equals("POST")) {
            var body = JSON.readTree(exchange.getRequestBody());
            if (body.path("merchantId").asLong() != 10 || body.path("items").size() != 1
                    || body.path("items").get(0).path("quantity").asInt() != 3) {
                send(exchange, 400, "{}");
                return;
            }
        } else if (!release || !exchange.getRequestMethod().equals("PUT")) {
            send(exchange, 400, "{}");
            return;
        }
        if (validate) {
            send(exchange, 200, SNAPSHOT);
            return;
        }
        String orderNo = path.split("/")[3];
        if (deduct) {
            DEDUCTIONS.incrementAndGet();
            if (OPERATIONS.putIfAbsent(orderNo, "DEDUCTED") == null) STOCK.addAndGet(-3);
            if (slowDeduction) {
                try {
                    Thread.sleep(1200); // Remote commit already happened; only the response is late.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            send(exchange, 201, operation(orderNo, "DEDUCTED", SNAPSHOT));
        } else {
            RELEASES.incrementAndGet();
            if (failedRelease) {
                send(exchange, 503, "{}");
                return;
            }
            if ("DEDUCTED".equals(OPERATIONS.put(orderNo, "RELEASED"))) STOCK.addAndGet(3);
            send(exchange, 200, operation(orderNo, "RELEASED", "[]"));
        }
    }

    private static String operation(String orderNo, String state, String snapshots) {
        return "{\"orderNo\":\"" + orderNo + "\",\"status\":\"" + state + "\",\"items\":" + snapshots + "}";
    }

    private static void send(HttpExchange exchange, int status, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private OrderDtos.Request request() {
        return new OrderDtos.Request("student-1", 10L,
                List.of(new OrderDtos.ItemRequest(1L, 1), new OrderDtos.ItemRequest(1L, 2)), "宿舍", null);
    }

    private ApiException fails(String code, int status) {
        var error = assertThrows(ApiException.class, () -> orders.createOrder(request()));
        assertEquals(code, error.code);
        assertEquals(status, error.status);
        assertEquals(0, repository.count());
        assertEquals(0, items.count());
        return error;
    }

    @Test
    void realFeignContractsCreateJsonOrderAndUseBothInstances() {
        var result = orders.createOrder(request());
        assertEquals(new BigDecimal("37.50"), result.totalAmount());
        assertEquals(3, orders.getOrder(result.orderNo()).items().getFirst().quantity());
        assertEquals(17, STOCK.get());
        assertEquals(1, DEDUCTIONS.get());
        assertTrue(FIRST_HITS.get() > 0 && SECOND_HITS.get() > 0, "Both discovered instances must receive requests");
        assertEquals(1, repository.count());
        assertEquals("SUCCEEDED", attempts.findById(result.orderNo()).orElseThrow().status);
    }

    @Test
    void noInstanceIs503AndStopsBeforeAnyWrite() {
        when(discovery.getInstances("user-service")).thenReturn(List.of());
        fails("DEPENDENCY_UNAVAILABLE", 503);
        assertEquals(0, attempts.count());
        assertEquals(0, FIRST_HITS.get() + SECOND_HITS.get());
    }

    @Test
    void expected404IsDistinctFromServerFailure() {
        USER_STATUS.set(404);
        fails("USER_NOT_FOUND", 404);
        USER_STATUS.set(500);
        fails("DEPENDENCY_UNAVAILABLE", 503);
        assertEquals(0, attempts.count());
    }

    @Test
    void unavailableDishNeverCreatesAnOrderOrAttempt() {
        DISH_STATUS.set(503);
        fails("DEPENDENCY_UNAVAILABLE", 503);
        assertEquals(0, attempts.count());
        assertEquals(20, STOCK.get());
    }

    @Test
    void malformedJsonIsNotBusinessSuccess() {
        malformedUser = true;
        fails("DEPENDENCY_BAD_RESPONSE", 502);
        assertEquals(0, attempts.count());
    }

    @Test
    void writeCommittedBeforeTimeoutIsCompensatedWithoutRetry() {
        slowDeduction = true;
        var error = fails("DEPENDENCY_TIMEOUT", 504);
        assertNotNull(error.orderNo);
        assertEquals(1, DEDUCTIONS.get(), "No automatic write retry");
        assertEquals(1, RELEASES.get());
        assertEquals("RELEASED", OPERATIONS.get(error.orderNo));
        assertEquals(20, STOCK.get());
        assertEquals("FAILED", attempts.findById(error.orderNo).orElseThrow().status);
    }

    @Test
    void ambiguousWriteAndUnavailableCompensationRequireReconciliation() {
        slowDeduction = true;
        failedRelease = true;
        var error = fails("ORDER_RECOVERY_REQUIRED", 503);
        assertEquals("DEDUCTED", OPERATIONS.get(error.orderNo));
        assertEquals(17, STOCK.get(), "503 must not be interpreted as remote write not executed");
        assertEquals(1, DEDUCTIONS.get());
        assertEquals("RECOVERY_REQUIRED", attempts.findById(error.orderNo).orElseThrow().status);
    }

    @AfterAll
    static void stop() {
        USERS.stop(0);
        DISH_ONE.stop(0);
        DISH_TWO.stop(0);
        HTTP_THREADS.shutdownNow();
    }
}
