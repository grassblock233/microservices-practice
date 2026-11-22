package com.zjgsu.lht.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zjgsu.lht.dto.OrderDtos;
import com.zjgsu.lht.exception.ApiException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
class FeignClientTest {
    private static final String SNAPSHOT =
            "[{\"dishId\":1,\"dishName\":\"鸡腿饭\",\"unitPrice\":12.50,\"quantity\":3}]";
    private static final ExecutorService HTTP_THREADS = Executors.newVirtualThreadPerTaskExecutor();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger USER_STATUS = new AtomicInteger(200);
    private static final AtomicInteger DISH_STATUS = new AtomicInteger(200);
    private static final AtomicInteger FIRST_HITS = new AtomicInteger();
    private static final AtomicInteger SECOND_HITS = new AtomicInteger();
    private static final AtomicInteger DEDUCTIONS = new AtomicInteger();
    private static final AtomicInteger STOCK = new AtomicInteger();
    private static volatile boolean malformedUser;
    private static volatile boolean slowDeduction;
    private static final HttpServer USERS = start(true, new AtomicInteger());
    private static final HttpServer DISH_ONE = start(false, FIRST_HITS);
    private static final HttpServer DISH_TWO = start(false, SECOND_HITS);

    @Autowired
    OrderService orders;
    @Autowired
    OrderRepository repository;
    @Autowired
    OrderItemRepository items;
    @MockitoBean
    DiscoveryClient discovery;

    @BeforeEach
    void reset() {
        items.deleteAll();
        repository.deleteAll();
        USER_STATUS.set(200);
        DISH_STATUS.set(200);
        FIRST_HITS.set(0);
        SECOND_HITS.set(0);
        DEDUCTIONS.set(0);
        STOCK.set(20);
        malformedUser = slowDeduction = false;
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
        if (!path.equals("/internal/stock/deduct") || !exchange.getRequestMethod().equals("POST")) {
            send(exchange, 400, "{}");
            return;
        }
        if (DISH_STATUS.get() != 200) {
            send(exchange, DISH_STATUS.get(), "{\"code\":\"DISH_NOT_FOUND\"}");
            return;
        }
        var body = JSON.readTree(exchange.getRequestBody());
        if (body.path("merchantId").asLong() != 10 || body.path("items").size() != 1
                || body.path("items").get(0).path("dishId").asLong() != 1
                || body.path("items").get(0).path("quantity").asInt() != 3) {
            send(exchange, 400, "{}");
            return;
        }
        DEDUCTIONS.incrementAndGet();
        STOCK.addAndGet(-3);
        if (slowDeduction) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        send(exchange, 200, SNAPSHOT);
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
    void realFeignContractsCreateOrdersAndUseBothInstances() {
        for (int i = 0; i < 2; i++) {
            var result = orders.createOrder(request());
            assertEquals(new BigDecimal("37.50"), result.totalAmount());
            assertEquals(3, orders.getOrder(result.orderNo(), request().userId(), false).items().getFirst().quantity());
        }
        assertEquals(14, STOCK.get());
        assertEquals(2, DEDUCTIONS.get());
        assertEquals(1, FIRST_HITS.get());
        assertEquals(1, SECOND_HITS.get());
        assertEquals(2, repository.count());
    }

    @Test
    void noUserInstanceStopsBeforeAnyWriteAndRecoveryAllowsNewOrder() {
        when(discovery.getInstances("user-service")).thenReturn(List.of());
        assertNull(fails("DEPENDENCY_UNAVAILABLE", 503).orderNo);
        assertEquals(0, FIRST_HITS.get() + SECOND_HITS.get());

        when(discovery.getInstances("user-service")).thenReturn(List.of(instance("u2", "user-service", USERS)));
        orders.createOrder(request());
        assertEquals(1, repository.count());
        assertEquals(1, DEDUCTIONS.get());
    }

    @Test
    void expected404IsDistinctFromServerFailure() {
        USER_STATUS.set(404);
        assertNull(fails("USER_NOT_FOUND", 404).orderNo);
        USER_STATUS.set(500);
        assertNull(fails("DEPENDENCY_UNAVAILABLE", 503).orderNo);
        verify(discovery, never()).getInstances("dish-service");
    }

    @Test
    void unavailableDishNeverCreatesAnOrder() {
        DISH_STATUS.set(503);
        var error = fails("DEPENDENCY_UNAVAILABLE", 503);
        assertNotNull(error.orderNo);
        assertEquals(1, FIRST_HITS.get() + SECOND_HITS.get());
        assertEquals(20, STOCK.get());
    }

    @Test
    void malformedJsonIsNotBusinessSuccess() {
        malformedUser = true;
        assertNull(fails("DEPENDENCY_BAD_RESPONSE", 502).orderNo);
        assertEquals(0, FIRST_HITS.get() + SECOND_HITS.get());
    }

    @Test
    void writeCommittedBeforeTimeoutIsNotRetriedOrReleased() {
        slowDeduction = true;
        var error = fails("DEPENDENCY_TIMEOUT", 504);
        assertNotNull(error.orderNo);
        assertTrue(error.getMessage().contains("人工核对"));
        assertEquals(1, DEDUCTIONS.get());
        assertEquals(1, FIRST_HITS.get() + SECOND_HITS.get(), "No write retry or release");
        assertEquals(17, STOCK.get(), "Timeout does not imply remote rollback");
    }

    @AfterAll
    static void stop() {
        USERS.stop(0);
        DISH_ONE.stop(0);
        DISH_TWO.stop(0);
        HTTP_THREADS.shutdownNow();
    }
}
