package com.zjgsu.lht.controller;

import com.zjgsu.lht.model.Dish;
import com.zjgsu.lht.model.DishStatus;
import com.zjgsu.lht.repository.DishRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StockControllerTest {
    @Autowired MockMvc mvc;
    @Autowired DishRepository dishes;
    private Long first;

    @BeforeEach
    void setup() {
        var dish = new Dish();
        dish.setMerchantId(10L);
        dish.setName("鸡腿饭");
        dish.setPrice(new BigDecimal("12.50"));
        dish.setStock(10);
        dish.setStatus(DishStatus.ON_SALE);
        first = dishes.saveAndFlush(dish).getId();
    }

    @AfterEach
    void cleanUp() {
        dishes.deleteAll();
    }

    @Test
    void returnsDeductionSnapshots() throws Exception {
        mvc.perform(post("/internal/stock/deduct").contentType("application/json").content(httpBody()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].dishId").value(first.intValue()))
                .andExpect(jsonPath("$[0].dishName").value("鸡腿饭"))
                .andExpect(jsonPath("$[0].unitPrice").value(12.50))
                .andExpect(jsonPath("$[0].quantity").value(3));
    }

    @Test
    void missingDishProducesBusinessError() throws Exception {
        mvc.perform(post("/internal/stock/deduct").contentType("application/json").content("""
                {"merchantId":10,"items":[{"dishId":9223372036854775807,"quantity":1}]}
                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DISH_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{", "{}", "{\"merchantId\":10,\"items\":[]}",
            """
            {"merchantId":10,"items":[null]}
            """,
            """
            {"merchantId":10,"items":[{"dishId":1,"quantity":1.5}]}
            """,
            """
            {"merchantId":10,"items":[{"dishId":1,"quantity":2147483647},{"dishId":1,"quantity":1}]}
            """
    })
    void rejectsInvalidRequests(String body) throws Exception {
        mvc.perform(post("/internal/stock/deduct").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private String httpBody() {
        return "{\"merchantId\":10,\"items\":[{\"dishId\":" + first + ",\"quantity\":3}]}";
    }

}
