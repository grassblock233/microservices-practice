package com.zjgsu.lht.service;

import com.zjgsu.lht.dto.OrderDtos;
import com.zjgsu.lht.exception.ApiException;
import com.zjgsu.lht.model.OrderStatus;
import com.zjgsu.lht.repository.OrderItemRepository;
import com.zjgsu.lht.repository.OrderRepository;
import com.zjgsu.lht.support.FeignRestTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@SpringBootTest(properties = {"spring.cloud.discovery.enabled=true", "spring.cloud.discovery.reactive.enabled=false"})
@ActiveProfiles("test")
@Import(FeignRestTestConfiguration.class)
class OrderServiceTest {
    private static final String SNAPSHOT = "[{\"dishId\":1,\"dishName\":\"鸡腿饭\",\"unitPrice\":13.00,\"quantity\":3}]";
    @Autowired OrderService service;
    @Autowired RestTemplate http;
    @Autowired OrderRepository orders;
    @MockitoSpyBean OrderItemRepository items;
    @MockitoBean DiscoveryClient discovery;
    MockRestServiceServer server;

    @BeforeEach
    void setup() {
        items.deleteAll();
        orders.deleteAll();
        server = MockRestServiceServer.bindTo(http).build();
        when(discovery.getInstances("user-service")).thenReturn(List.of(
                new DefaultServiceInstance("u1", "user-service", "192.0.2.11", 9081, false)));
        when(discovery.getInstances("dish-service")).thenReturn(List.of(
                new DefaultServiceInstance("d1", "dish-service", "192.0.2.12", 9082, false)));
    }

    @AfterEach
    void verifyHttp() {
        server.verify();
    }

    private OrderDtos.Request request() {
        return new OrderDtos.Request("student-1", 10L,
                List.of(new OrderDtos.ItemRequest(1L, 1), new OrderDtos.ItemRequest(1L, 2)), "宿舍1幢", null);
    }

    private ResponseActions userCall() {
        return server.expect(requestTo("http://192.0.2.11:9081/api/users/student-1"))
                .andExpect(method(HttpMethod.GET));
    }

    private void user(String status) {
        userCall().andRespond(withSuccess("""
                {"id":"student-1","displayName":"同学一","status":"%s"}
                """.formatted(status), MediaType.APPLICATION_JSON));
    }

    private ResponseActions deduction() {
        return server.expect(requestTo("http://192.0.2.12:9082/internal/stock/deduct"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.merchantId").value(10))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].dishId").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(3));
    }

    private ApiException fails(String code, int status) {
        var error = assertThrows(ApiException.class, () -> service.createOrder(request()));
        assertEquals(code, error.code);
        assertEquals(status, error.status);
        assertNoOrder();
        return error;
    }

    private void assertNoOrder() {
        assertEquals(0, orders.count());
        assertEquals(0, items.count());
    }

    @Test
    void createsOrderUsingAuthoritativePriceAndMergedSnapshots() {
        user("ACTIVE");
        deduction().andRespond(request -> {
            assertNoOrder();
            return withSuccess(SNAPSHOT, MediaType.APPLICATION_JSON).createResponse(request);
        });
        var result = service.createOrder(request());
        assertEquals(new BigDecimal("39.00"), result.totalAmount());
        assertEquals(OrderStatus.PENDING_PAYMENT, result.status());
        var detail = service.getOrder(result.orderNo(), request().userId(), false);
        assertEquals(1, detail.items().size());
        assertEquals(3, detail.items().getFirst().quantity());
        assertEquals("鸡腿饭", detail.items().getFirst().dishName());
        assertEquals(new BigDecimal("13.00"), detail.items().getFirst().unitPrice());
        assertEquals(1, service.getOrders("student-1").size());
    }

    @Test
    void readingOrderRequiresOwnerUnlessCallerIsAdmin() {
        user("ACTIVE");
        deduction().andRespond(withSuccess(SNAPSHOT, MediaType.APPLICATION_JSON));
        String orderNo = service.createOrder(request()).orderNo();

        for (String caller : Arrays.asList(null, "student-2")) {
            var error = assertThrows(ApiException.class, () -> service.getOrder(orderNo, caller, false));
            assertEquals("FORBIDDEN", error.code);
        }
        assertEquals(orderNo, service.getOrder(orderNo, "student-1", false).orderNo());
        assertEquals(orderNo, service.getOrder(orderNo, "admin", true).orderNo());
    }

    @Test
    void disabledUserStopsBeforeDeduction() {
        user("DISABLED");
        assertNull(fails("USER_DISABLED", 409).orderNo);
    }

    @Test
    void unavailableUserNeverFallsBack() {
        userCall().andRespond(withException(new java.net.ConnectException("refused")));
        assertNull(fails("DEPENDENCY_UNAVAILABLE", 503).orderNo);
    }

    @Test
    void discoveryFailureReturns503BeforeAnyWrite() {
        when(discovery.getInstances("user-service")).thenThrow(new IllegalStateException("discovery unavailable"));
        assertNull(fails("DEPENDENCY_UNAVAILABLE", 503).orderNo);
    }

    @Test
    void malformedUserResponseIsNotAccepted() {
        userCall().andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertNull(fails("DEPENDENCY_BAD_RESPONSE", 502).orderNo);
    }

    @Test
    void unexpected404CannotMasqueradeAsMissingUser() {
        userCall().andRespond(withStatus(HttpStatus.NOT_FOUND).body("<html>proxy 404</html>"));
        assertNull(fails("DEPENDENCY_BAD_RESPONSE", 502).orderNo);
    }

    @ParameterizedTest
    @CsvSource({"DISH_NOT_FOUND, 404", "MERCHANT_MISMATCH, 409", "DISH_OFF_SALE, 409", "INSUFFICIENT_STOCK, 409"})
    void stockRejectionKeepsBusinessError(String code, int status) {
        user("ACTIVE");
        deduction().andRespond(withStatus(HttpStatus.valueOf(status)).body("{\"code\":\"" + code + "\"}"));
        assertDoesNotThrow(() -> UUID.fromString(fails(code, status).orderNo));
    }

    @Test
    void noDishInstancesNeverCreatesOrder() {
        user("ACTIVE");
        when(discovery.getInstances("dish-service")).thenReturn(List.of());
        assertManualCheck(fails("DEPENDENCY_UNAVAILABLE", 503));
    }

    @Test
    void deductionTimeoutRequiresManualCheckWithoutRelease() {
        user("ACTIVE");
        deduction().andRespond(withException(new SocketTimeoutException("timeout")));
        assertManualCheck(fails("DEPENDENCY_TIMEOUT", 504));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "[{\"dishId\":1,\"dishName\":\"鸡腿饭\",\"unitPrice\":12.50,\"quantity\":2}]"})
    void invalidDeductionSnapshotRequiresManualCheck(String body) {
        user("ACTIVE");
        deduction().andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertManualCheck(fails("DEPENDENCY_BAD_RESPONSE", 502));
    }

    @Test
    void localOrderRollbackRequiresManualCheckWithoutRelease() {
        user("ACTIVE");
        deduction().andRespond(withSuccess(SNAPSHOT, MediaType.APPLICATION_JSON));
        // Fail after the order row is written, so its absence verifies transaction rollback.
        doThrow(new DataAccessResourceFailureException("controlled item write failure")).when(items).flush();
        assertManualCheck(fails("ORDER_SAVE_FAILED", 500));
    }

    private static void assertManualCheck(ApiException error) {
        assertDoesNotThrow(() -> UUID.fromString(error.orderNo));
        assertTrue(error.getMessage().contains("人工核对"));
        assertTrue(error.getMessage().contains("勿直接重试"));
    }

    @Test
    void mergedQuantityOverflowFailsBeforeAnyHttpCall() {
        var r = new OrderDtos.Request("student-1", 10L,
                List.of(new OrderDtos.ItemRequest(1L, Integer.MAX_VALUE), new OrderDtos.ItemRequest(1L, 1)), "宿舍", null);
        assertEquals("INVALID_REQUEST", assertThrows(ApiException.class, () -> service.createOrder(r)).code);
        assertNoOrder();
    }
}
