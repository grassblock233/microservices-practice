package com.zjgsu.lht.controller;

import com.zjgsu.lht.dto.OrderDtos;
import com.zjgsu.lht.exception.ApiException;
import com.zjgsu.lht.model.OrderStatus;
import com.zjgsu.lht.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
class OrderControllerTest {
    private static final String ORDER_NO = "order-1";
    private static final String OWNER = "student-1";
    private static final String OTHER_STUDENT = "student-2";
    private static final String ORDER_BODY = """
            {"userId":"student-1","merchantId":10,"items":[{"dishId":1,"quantity":1}],"deliveryAddress":"宿舍"}
            """;

    @Autowired MockMvc mvc;
    @MockitoBean OrderService service;

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{",
            "{\"userId\":\"student-1\",\"merchantId\":10,\"items\":[null],\"deliveryAddress\":\"宿舍\"}"})
    void rejectsInvalidOrderBodiesBeforeCallingService(String body) throws Exception {
        mvc.perform(create(OWNER, "STUDENT", body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void onlyTheMatchingStudentCanCreateAnOrder() throws Exception {
        mvc.perform(create(OTHER_STUDENT, "STUDENT", ORDER_BODY)).andExpect(status().isForbidden());
        mvc.perform(create(OWNER, "ADMIN", ORDER_BODY)).andExpect(status().isForbidden());
        verifyNoInteractions(service);

        when(service.createOrder(any())).thenReturn(
                new OrderDtos.Response(ORDER_NO, new BigDecimal("12.50"), OrderStatus.PENDING_PAYMENT));
        mvc.perform(create(OWNER, "STUDENT", ORDER_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/orders/" + ORDER_NO))
                .andExpect(jsonPath("$.orderNo").value(ORDER_NO));
        verify(service).createOrder(new OrderDtos.Request(
                OWNER, 10L, List.of(new OrderDtos.ItemRequest(1L, 1)), "宿舍", null));
    }

    @Test
    void exposesOrderNumberForFailedDeductionOrSave() throws Exception {
        when(service.createOrder(any()))
                .thenThrow(new ApiException(504, "DEPENDENCY_TIMEOUT", "库存可能已扣减，请人工核对", ORDER_NO, null))
                .thenThrow(new ApiException(500, "ORDER_SAVE_FAILED", "库存已扣减，请人工核对", ORDER_NO, null));

        mvc.perform(create(OWNER, "STUDENT", ORDER_BODY)).andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_TIMEOUT"))
                .andExpect(jsonPath("$.orderNo").value(ORDER_NO));
        mvc.perform(create(OWNER, "STUDENT", ORDER_BODY)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("ORDER_SAVE_FAILED"))
                .andExpect(jsonPath("$.orderNo").value(ORDER_NO));
    }

    @Test
    void rejectsForeignListQueriesAndAnonymousDetailRequestsBeforeCallingService() throws Exception {
        mvc.perform(get("/api/orders").param("userId", OWNER)
                        .header("X-User-Id", OTHER_STUDENT).header("X-User-Role", "STUDENT"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/orders/{orderNo}", ORDER_NO)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void passesStudentIdentityAndAdminPrivilegeToDetailService() throws Exception {
        mvc.perform(get("/api/orders/{orderNo}", ORDER_NO)
                        .header("X-User-Id", OWNER).header("X-User-Role", "STUDENT"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/orders/{orderNo}", ORDER_NO)
                        .header("X-User-Id", "admin").header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk());
        verify(service).getOrder(ORDER_NO, OWNER, false);
        verify(service).getOrder(ORDER_NO, "admin", true);
    }

    private static MockHttpServletRequestBuilder create(String caller, String role, String body) {
        return post("/api/orders").header("X-User-Id", caller).header("X-User-Role", role)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
