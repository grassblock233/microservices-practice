package com.zjgsu.lht.controller;

import com.zjgsu.lht.dto.OrderDtos;
import com.zjgsu.lht.exception.InvalidRequestException;
import com.zjgsu.lht.exception.OrderConflictException;
import com.zjgsu.lht.exception.ResourceNotFoundException;
import com.zjgsu.lht.model.OrderStatus;
import com.zjgsu.lht.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 使用模拟 Service 验证下单、订单列表和详情接口的 HTTP 契约、参数校验及异常响应。
 */
@WebMvcTest(OrderController.class)
@ActiveProfiles("test")
class OrderControllerTests {
    private static final String ORDER_NO = "123e4567-e89b-12d3-a456-426614174000";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 24, 12, 30);
    private static final String VALID_BODY = """
            {"userId":"student-1","merchantId":10,
             "items":[{"dishId":7,"quantity":2}],
             "deliveryAddress":"学生宿舍 1 幢","remark":"少辣"}
            """;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OrderService orderService;

    /**
     * 验证合法下单返回 201、详情地址 Location 和待支付结果，并正确映射请求字段。
     */
    @Test
    void createsOrderAndReturnsLocationAndPendingPaymentResponse() throws Exception {
        when(orderService.createOrder(any())).thenReturn(new OrderDtos.Response(
                ORDER_NO, new BigDecimal("25.00"), OrderStatus.PENDING_PAYMENT));

        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/orders/" + ORDER_NO))
                .andExpect(jsonPath("$.orderNo").value(ORDER_NO))
                .andExpect(jsonPath("$.totalAmount").value(25.00))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        verify(orderService).createOrder(new OrderDtos.Request("student-1", 10L,
                List.of(new OrderDtos.ItemRequest(7L, 2)), "学生宿舍 1 幢", "少辣"));
    }

    /**
     * 验证下单可省略选填备注，并将缺省备注作为 null 传入业务层。
     */
    @Test
    void acceptsOmittedOptionalRemark() throws Exception {
        when(orderService.createOrder(any())).thenReturn(new OrderDtos.Response(
                ORDER_NO, new BigDecimal("25.00"), OrderStatus.PENDING_PAYMENT));
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace(",\"remark\":\"少辣\"", "")))
                .andExpect(status().isCreated());
        verify(orderService).createOrder(argThat(request -> request.remark() == null));
    }

    /**
     * 验证每种非法请求体均返回 400 和 ProblemDetail，且不会调用下单业务。
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void rejectsInvalidBodyBeforeCallingService(String scenario, String body) throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail", not(emptyOrNullString())));
        verifyNoInteractions(orderService);
    }

    /**
     * 为请求校验提供缺失字段、长度越界、嵌套明细、非法数量和 JSON 格式错误等参数。
     */
    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("缺少学生", VALID_BODY.replace("\"userId\":\"student-1\",", "")),
                Arguments.of("空白学生", VALID_BODY.replace("student-1", " ")),
                Arguments.of("学生编号过长", VALID_BODY.replace("student-1", "s".repeat(65))),
                Arguments.of("缺少商家", VALID_BODY.replace("\"merchantId\":10,", "")),
                Arguments.of("零商家编号", VALID_BODY.replace("\"merchantId\":10", "\"merchantId\":0")),
                Arguments.of("负商家编号", VALID_BODY.replace("\"merchantId\":10", "\"merchantId\":-1")),
                Arguments.of("缺少明细", VALID_BODY.replace("\"items\":[{\"dishId\":7,\"quantity\":2}],", "")),
                Arguments.of("空明细", VALID_BODY.replace("[{\"dishId\":7,\"quantity\":2}]", "[]")),
                Arguments.of("明细为 null", VALID_BODY.replace("[{\"dishId\":7,\"quantity\":2}]", "null")),
                Arguments.of("明细元素为 null", VALID_BODY.replace("[{\"dishId\":7,\"quantity\":2}]", "[null]")),
                Arguments.of("缺少菜品", VALID_BODY.replace("\"dishId\":7,", "")),
                Arguments.of("零菜品编号", VALID_BODY.replace("\"dishId\":7", "\"dishId\":0")),
                Arguments.of("负菜品编号", VALID_BODY.replace("\"dishId\":7", "\"dishId\":-1")),
                Arguments.of("缺少数量", VALID_BODY.replace(",\"quantity\":2", "")),
                Arguments.of("零数量", VALID_BODY.replace("\"quantity\":2", "\"quantity\":0")),
                Arguments.of("负数量", VALID_BODY.replace("\"quantity\":2", "\"quantity\":-1")),
                Arguments.of("小数数量", VALID_BODY.replace("\"quantity\":2", "\"quantity\":1.5")),
                Arguments.of("非数字数量", VALID_BODY.replace("\"quantity\":2", "\"quantity\":\"abc\"")),
                Arguments.of("数量超出整数范围", VALID_BODY.replace("\"quantity\":2", "\"quantity\":2147483648")),
                Arguments.of("缺少地址", VALID_BODY.replace("\"deliveryAddress\":\"学生宿舍 1 幢\",", "")),
                Arguments.of("空白地址", VALID_BODY.replace("学生宿舍 1 幢", " ")),
                Arguments.of("地址过长", VALID_BODY.replace("学生宿舍 1 幢", "地".repeat(256))),
                Arguments.of("备注过长", VALID_BODY.replace("少辣", "字".repeat(501))),
                Arguments.of("非法 JSON", "{"),
                Arguments.of("空请求体", ""));
    }

    /**
     * 验证订单列表缺少学生编号或编号为空白时返回 400，且不触发查询。
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingOrBlankListUserId(String userId) throws Exception {
        var request = get("/api/orders");
        if (userId != null) {
            request.param("userId", userId);
        }
        mvc.perform(request).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail", not(emptyOrNullString())));
        verifyNoInteractions(orderService);
    }

    /**
     * 验证订单列表拒绝超过 64 字符的学生编号，避免非法参数进入业务层。
     */
    @Test
    void rejectsOverlongListUserId() throws Exception {
        mvc.perform(get("/api/orders").param("userId", "s".repeat(65)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(orderService);
    }

    /**
     * 验证学生编号正确传给查询服务，响应仅包含订单摘要字段，不携带明细。
     */
    @Test
    void returnsOrderSummariesForRequestedStudent() throws Exception {
        when(orderService.getOrders("student-1")).thenReturn(List.of(new OrderDtos.SummaryResponse(
                ORDER_NO, new BigDecimal("25.00"), OrderStatus.PENDING_PAYMENT, CREATED_AT)));
        mvc.perform(get("/api/orders").param("userId", "student-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderNo").value(ORDER_NO))
                .andExpect(jsonPath("$[0].totalAmount").value(25.00))
                .andExpect(jsonPath("$[0].status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-10-24T12:30:00"))
                .andExpect(jsonPath("$[0].items").doesNotExist());
        verify(orderService).getOrders("student-1");
    }

    /**
     * 验证学生没有订单时正常返回 HTTP 200 和空数组。
     */
    @Test
    void returnsEmptyArrayWhenStudentHasNoOrders() throws Exception {
        when(orderService.getOrders("student-1")).thenReturn(List.of());
        mvc.perform(get("/api/orders").param("userId", "student-1"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    /**
     * 验证详情接口完整返回订单基本信息及明细中的名称、单价、数量和小计快照。
     */
    @Test
    void returnsOrderDetailAndItemSnapshots() throws Exception {
        when(orderService.getOrder(ORDER_NO)).thenReturn(new OrderDtos.DetailResponse(ORDER_NO,
                "student-1", 10L, new BigDecimal("25.00"), OrderStatus.PENDING_PAYMENT,
                "学生宿舍 1 幢", "少辣", CREATED_AT, List.of(new OrderDtos.ItemResponse(
                7L, "鸡腿饭", new BigDecimal("12.50"), 2, new BigDecimal("25.00")))));
        mvc.perform(get("/api/orders/{orderNo}", ORDER_NO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(ORDER_NO))
                .andExpect(jsonPath("$.userId").value("student-1"))
                .andExpect(jsonPath("$.merchantId").value(10))
                .andExpect(jsonPath("$.totalAmount").value(25.00))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.deliveryAddress").value("学生宿舍 1 幢"))
                .andExpect(jsonPath("$.remark").value("少辣"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-24T12:30:00"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].dishId").value(7))
                .andExpect(jsonPath("$.items[0].dishName").value("鸡腿饭"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(12.50))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].subtotal").value(25.00));
        verify(orderService).getOrder(ORDER_NO);
    }

    /**
     * 验证业务异常映射为预期的 400、404 或 409，并以 ProblemDetail 保留失败原因。
     */
    @ParameterizedTest
    @MethodSource("businessErrors")
    void mapsBusinessExceptionsToProblemDetail(RuntimeException failure, int expectedStatus) throws Exception {
        when(orderService.createOrder(any())).thenThrow(failure);
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.detail").value(failure.getMessage()));
    }

    /**
     * 提供资源不存在、商家冲突、下架、库存不足和数量溢出的异常及预期状态码。
     */
    static Stream<Arguments> businessErrors() {
        return Stream.of(
                Arguments.of(new ResourceNotFoundException("菜品不存在：7"), 404),
                Arguments.of(new OrderConflictException("菜品不属于请求商家：7"), 409),
                Arguments.of(new OrderConflictException("菜品已下架：7"), 409),
                Arguments.of(new OrderConflictException("菜品库存不足：7"), 409),
                Arguments.of(new InvalidRequestException("菜品购买数量超出允许范围：7"), 400));
    }

    /**
     * 验证不存在的订单返回 404，并在 ProblemDetail 中说明订单号和失败原因。
     */
    @Test
    void returnsProblemDetailWhenOrderDoesNotExist() throws Exception {
        when(orderService.getOrder(ORDER_NO)).thenThrow(new ResourceNotFoundException("订单不存在：" + ORDER_NO));
        mvc.perform(get("/api/orders/{orderNo}", ORDER_NO))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("订单不存在：" + ORDER_NO));
    }
}
