package com.zjgsu.lht.controller;

import com.zjgsu.lht.model.Dish;
import com.zjgsu.lht.model.DishStatus;
import com.zjgsu.lht.repository.DishRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DishControllerTest {
    @Autowired MockMvc mvc;
    @Autowired DishRepository dishes;

    @Test
    void dishListHttpFiltersMerchantAndStatusAndKeepsSoldOutDishesInIdOrder() throws Exception {
        var first = seed(10, "鸡腿饭", 10, DishStatus.ON_SALE);
        var second = seed(10, "牛肉面", 10, DishStatus.ON_SALE);
        seed(20, "其他商家", 10, DishStatus.ON_SALE);
        seed(10, "下架菜品", 10, DishStatus.OFF_SALE);
        var soldOut = seed(10, "售罄菜品", 0, DishStatus.ON_SALE);
        mvc.perform(get("/api/dishes").param("merchantId", "10"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[0].merchantId").value(10))
                .andExpect(jsonPath("$[0].name").value("鸡腿饭"))
                .andExpect(jsonPath("$[0].price").value(12.50))
                .andExpect(jsonPath("$[0].status").value("ON_SALE"))
                .andExpect(jsonPath("$[1].id").value(second))
                .andExpect(jsonPath("$[2].id").value(soldOut))
                .andExpect(jsonPath("$[2].stock").value(0));
        mvc.perform(get("/api/dishes").param("merchantId", "999"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    private Long seed(long merchant, String name, int quantity, DishStatus status) {
        var d = new Dish();
        d.setMerchantId(merchant);
        d.setName(name);
        d.setPrice(new BigDecimal("12.50"));
        d.setStock(quantity);
        d.setStatus(status);
        return dishes.saveAndFlush(d).getId();
    }
}
