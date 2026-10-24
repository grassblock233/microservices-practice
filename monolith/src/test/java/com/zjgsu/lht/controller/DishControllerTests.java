package com.zjgsu.lht.controller;

import com.zjgsu.lht.dto.DishDtos;
import com.zjgsu.lht.model.DishStatus;
import com.zjgsu.lht.service.DishService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 隔离菜品业务层，验证菜品查询接口的响应映射、空结果和商家参数校验。
 */
@WebMvcTest(DishController.class)
@ActiveProfiles("test")
class DishControllerTests {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    DishService dishService;

    /**
     * 验证查询成功返回全部菜品字段，并保留库存为零的上架菜品。
     */
    @Test
    void returnsDishFieldsIncludingSoldOutStock() throws Exception {
        when(dishService.getDishes(10L)).thenReturn(List.of(new DishDtos.Response(
                7L, 10L, "鸡腿饭", "主食", new BigDecimal("12.50"), 0, DishStatus.ON_SALE)));

        mvc.perform(get("/api/dishes").param("merchantId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].merchantId").value(10))
                .andExpect(jsonPath("$[0].name").value("鸡腿饭"))
                .andExpect(jsonPath("$[0].category").value("主食"))
                .andExpect(jsonPath("$[0].price").value(12.50))
                .andExpect(jsonPath("$[0].stock").value(0))
                .andExpect(jsonPath("$[0].status").value("ON_SALE"));
        verify(dishService).getDishes(10L);
    }

    /**
     * 验证商家没有可返回菜品时，接口以 HTTP 200 返回空数组。
     */
    @Test
    void returnsEmptyArrayWhenMerchantHasNoDishes() throws Exception {
        when(dishService.getDishes(10L)).thenReturn(List.of());
        mvc.perform(get("/api/dishes").param("merchantId", "10"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    /**
     * 逐一验证缺失、空白、非正数及格式错误的商家编号均返回 400，且不调用业务层。
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "0", "-1", "abc", "1.5"})
    void rejectsInvalidMerchantIdBeforeCallingService(String merchantId) throws Exception {
        var request = get("/api/dishes");
        if (merchantId != null) {
            request.param("merchantId", merchantId);
        }
        mvc.perform(request).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail", not(emptyOrNullString())));
        verifyNoInteractions(dishService);
    }
}
