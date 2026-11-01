package com.zjgsu.lht;
import com.zjgsu.lht.dto.*;
import com.zjgsu.lht.exception.ApiException;
import com.zjgsu.lht.repository.*;
import com.zjgsu.lht.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
@SpringBootTest @org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc @ActiveProfiles("test")
class OrderFlowTests {
    @Autowired OrderService service;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired RestTemplate http;
    @Autowired OrderRepository orders;
    @Autowired OrderItemRepository items;
    @Autowired OrderAttemptRepository attempts;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean OrderWriter writer;
    @MockitoSpyBean OrderItemRepository itemSpy;
    MockRestServiceServer server;
    static final String SNAPSHOT="[{\"dishId\":1,\"dishName\":\"鸡腿饭\",\"unitPrice\":12.50,\"quantity\":3}]";
    @BeforeEach void setup() {
        reset(writer,itemSpy); items.deleteAll();orders.deleteAll();attempts.deleteAll();
        server=MockRestServiceServer.bindTo(http).build();
    }
    @AfterEach void verifyHttp() { server.verify(); }
    OrderDtos.Request request() { return new OrderDtos.Request("student-1",10L,
            List.of(new OrderDtos.ItemRequest(1L,1),new OrderDtos.ItemRequest(1L,2)),"宿舍1幢",null); }
    void user(String status) {
        server.expect(requestTo("http://user-service:8081/api/users/student-1"))
            .andRespond(withSuccess("{\"id\":\"student-1\",\"displayName\":\"同学一\",\"status\":\""+status+"\"}",MediaType.APPLICATION_JSON));
    }
    void prevalidate() { user("ACTIVE");server.expect(requestTo("http://dish-service:8082/internal/dishes/validate"))
        .andExpect(method(HttpMethod.POST)).andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].quantity").value(3)).andRespond(withSuccess(SNAPSHOT,MediaType.APPLICATION_JSON)); }
    String orderNo() { return attempts.findAll().getFirst().orderNo; }
    void deduct() { deduct(SNAPSHOT); }
    void deduct(String snapshots) { deduct(snapshots,HttpStatus.CREATED); }
    void deduct(String snapshots,HttpStatus responseStatus) {
        server.expect(request -> assertTrue(request.getURI().getPath().endsWith("/deduct")))
            .andRespond(request -> withStatus(responseStatus).contentType(MediaType.APPLICATION_JSON).body("{\"orderNo\":\""+orderNo()+"\",\"status\":\"DEDUCTED\",\"items\":"+snapshots+"}").createResponse(request));
    }
    void release(boolean success) {
        server.expect(request -> assertEquals("/internal/stock-operations/"+orderNo()+"/release",request.getURI().getPath()))
            .andExpect(method(HttpMethod.PUT)).andRespond(request -> success
                ? withSuccess("{\"orderNo\":\""+orderNo()+"\",\"status\":\"RELEASED\",\"items\":[]}",MediaType.APPLICATION_JSON).createResponse(request)
                : withServerError().createResponse(request));
    }
    ApiException fails(String code) { var e=assertThrows(ApiException.class,() -> service.createOrder(request()));assertEquals(code,e.code);return e; }
    void empty() { assertEquals(0,orders.count());assertEquals(0,items.count()); }
    @Test void createsOrderUsingDeductedPriceAndMergedSnapshots() {
        prevalidate();deduct();var result=service.createOrder(request());
        assertEquals(new BigDecimal("37.50"),result.totalAmount());assertEquals("SUCCEEDED",attempts.findById(result.orderNo()).orElseThrow().status);
        var detail=service.getOrder(result.orderNo());assertEquals(1,detail.items().size());assertEquals(3,detail.items().getFirst().quantity());
        assertEquals("鸡腿饭",detail.items().getFirst().dishName());assertEquals(1,service.getOrders("student-1").size());
    }
    @Test void amountUsesAuthoritativeDeductionSnapshot() {
        prevalidate();deduct(SNAPSHOT.replace("12.50","13.00"));
        assertEquals(new BigDecimal("39.00"),service.createOrder(request()).totalAmount());
    }
    @Test void invalidHttpRequestsAreRejectedBeforeClientsAreCalled() throws Exception {
        for (String body:new String[]{"{}", "{", "{\"userId\":\"student-1\",\"merchantId\":10,\"items\":[null],\"deliveryAddress\":\"宿舍\"}"})
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders")
                    .contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("INVALID_REQUEST"));
        assertEquals(0,attempts.count());
    }
    @Test void healthOnlyChecksOwnDatabase() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("UP"));
    }
    @Test void disabledUserNeverCreatesLocalRecords() { user("DISABLED");fails("USER_DISABLED");empty();assertEquals(0,attempts.count()); }
    @Test void missingUserNeverCallsDishOrCreatesRecords() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"code\":\"USER_NOT_FOUND\"}"));
        fails("USER_NOT_FOUND");empty();assertEquals(0,attempts.count());
    }
    @Test void missingDishNeverCreatesAttempt() {
        user("ACTIVE");server.expect(anything()).andRespond(withStatus(HttpStatus.NOT_FOUND).body("{\"code\":\"DISH_NOT_FOUND\"}"));
        fails("DISH_NOT_FOUND");empty();assertEquals(0,attempts.count());
    }
    @Test void unavailableUserNeverFallsBack() {
        server.expect(anything()).andRespond(withException(new java.net.ConnectException("refused")));
        assertEquals(503,fails("DEPENDENCY_UNAVAILABLE").status);empty();assertEquals(0,attempts.count());
    }
    @Test void prevalidationTimeoutCreatesNoAttempt() {
        user("ACTIVE");server.expect(anything()).andRespond(withException(new SocketTimeoutException("timeout")));
        assertEquals(504,fails("DEPENDENCY_TIMEOUT").status);empty();assertEquals(0,attempts.count());
    }
    @Test void malformedUserResponseIsNotAccepted() {
        server.expect(anything()).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        fails("DEPENDENCY_BAD_RESPONSE");empty();assertEquals(0,attempts.count());
    }
    @Test void unexpected404CannotMasqueradeAsMissingUser() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.NOT_FOUND).body("<html>proxy 404</html>"));
        assertEquals(502,fails("DEPENDENCY_BAD_RESPONSE").status);assertEquals(0,attempts.count());
    }
    @Test void explicitStockRejectionDoesNotReleaseOrWriteOrder() {
        prevalidate();server.expect(anything()).andRespond(withStatus(HttpStatus.CONFLICT).body("{\"code\":\"INSUFFICIENT_STOCK\"}"));
        var e=fails("INSUFFICIENT_STOCK");empty();assertEquals(orderNo(),e.orderNo);assertEquals("FAILED",attempts.findAll().getFirst().status);
    }
    @Test void deductionTimeoutReleasesSameOperationAndMarksFailed() {
        prevalidate();server.expect(anything()).andRespond(withException(new SocketTimeoutException("timeout")));release(true);
        var e=fails("DEPENDENCY_TIMEOUT");empty();assertEquals(orderNo(),e.orderNo);assertEquals("FAILED",attempts.findAll().getFirst().status);
    }
    @Test void deduction5xxAndFailedReleaseRequireRecovery() {
        prevalidate();server.expect(anything()).andRespond(withServerError());release(false);
        fails("ORDER_RECOVERY_REQUIRED");empty();assertEquals("RECOVERY_REQUIRED",attempts.findAll().getFirst().status);
        assertTrue(attempts.findAll().getFirst().failureDetail.contains("compensation="));
    }
    @Test void acceptedButNotCommittedResponseCannotCreateOrder() {
        prevalidate();deduct(SNAPSHOT,HttpStatus.ACCEPTED);release(true);
        fails("DEPENDENCY_BAD_RESPONSE");empty();assertEquals("FAILED",attempts.findAll().getFirst().status);
    }
    @Test void invalidDeductionResponseIsCompensated() {
        prevalidate();server.expect(anything()).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));release(true);
        fails("DEPENDENCY_BAD_RESPONSE");empty();assertEquals("FAILED",attempts.findAll().getFirst().status);
    }
    @Test void actualOrderTransactionRollsBackThenReleasesStock() {
        prevalidate();deduct();release(true);
        // Fail at item flush AFTER orders.saveAndFlush; the transaction proxy must roll back the order row.
        doThrow(new DataAccessResourceFailureException("controlled item write failure")).when(itemSpy).flush();
        fails("ORDER_SAVE_FAILED");empty();assertEquals("FAILED",attempts.findAll().getFirst().status);
    }
    @Test void failedCompensationAfterLocalRollbackLeavesRecoveryRecord() {
        prevalidate();deduct();release(false);
        doThrow(new DataAccessResourceFailureException("controlled failure")).when(itemSpy).flush();
        fails("ORDER_RECOVERY_REQUIRED");empty();assertEquals("RECOVERY_REQUIRED",attempts.findAll().getFirst().status);
    }
    @Test void cannotDetermineCommitMustNotReleaseStock() {
        prevalidate();deduct();
        doThrow(new DataAccessResourceFailureException("write")).when(writer).save(anyString(),any(),anyList());
        doThrow(new DataAccessResourceFailureException("lookup")).when(writer).committed(anyString());
        fails("ORDER_RECOVERY_REQUIRED");assertEquals("RECOVERY_REQUIRED",attempts.findAll().getFirst().status);
    }
    @Test void confirmedCommitAfterExceptionMustNotReleaseStock() {
        prevalidate();deduct();
        var committed=new OrderDtos.Response("confirmed-order",new BigDecimal("37.50"),com.zjgsu.lht.model.OrderStatus.PENDING_PAYMENT);
        doThrow(new DataAccessResourceFailureException("commit response lost")).when(writer).save(anyString(),any(),anyList());
        doReturn(Optional.of(committed)).when(writer).committed(anyString());
        assertEquals(committed,service.createOrder(request()));
    }

    @Test void attemptWriteFailureMustNotCallDeduction() {
        prevalidate();
        jdbc.execute("ALTER TABLE order_attempts ADD CONSTRAINT reject_attempt CHECK (status <> 'PROCESSING')");
        try { fails("ORDER_SAVE_FAILED");empty();assertEquals(0,attempts.count()); }
        finally { jdbc.execute("ALTER TABLE order_attempts DROP CONSTRAINT reject_attempt"); }
    }
    @Test void mergedQuantityOverflowFailsBeforeAnyHttpCall() {
        var r=new OrderDtos.Request("student-1",10L,List.of(new OrderDtos.ItemRequest(1L,Integer.MAX_VALUE),new OrderDtos.ItemRequest(1L,1)),"宿舍",null);
        assertEquals("INVALID_REQUEST",assertThrows(ApiException.class,() -> service.createOrder(r)).code);assertEquals(0,attempts.count());
    }
}
