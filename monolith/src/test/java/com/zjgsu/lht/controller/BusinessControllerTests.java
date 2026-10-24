package com.zjgsu.lht.controller;

import com.zjgsu.lht.dto.OrderDtos;
import com.zjgsu.lht.model.Dish;
import com.zjgsu.lht.model.DishStatus;
import com.zjgsu.lht.repository.DishRepository;
import com.zjgsu.lht.repository.OrderItemRepository;
import com.zjgsu.lht.repository.OrderRepository;
import com.zjgsu.lht.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class BusinessControllerTests {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private DishRepository dishRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private OrderService orderService;

    private Dish dish;

    @BeforeEach
    void prepareData() {
        orderItemRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        dishRepository.deleteAllInBatch();
        dish = new Dish();
        dish.setMerchantId(10L);
        dish.setName("鸡腿饭");
        dish.setCategory("主食");
        dish.setPrice(new BigDecimal("12.50"));
        dish.setStock(20);
        dish.setStatus(DishStatus.ON_SALE);
        dish = dishRepository.saveAndFlush(dish);
    }

    /** 从 HTTP 创建到 Location 详情查询，验证两个接口使用真实服务和数据库完成业务。 */
    @Test
    void createsOrderAndReturnsItsDetailLocation() throws Exception {
        String location = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(body("[{\"dishId\":" + dish.getId() + ",\"quantity\":2}]")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.orderNo").isString())
                .andExpect(jsonPath("$.totalAmount").value(25.00))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andReturn().getResponse().getHeader("Location");

        assertThat(location).startsWith("http://localhost/api/orders/");
        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("student-1"))
                .andExpect(jsonPath("$.merchantId").value(10))
                .andExpect(jsonPath("$.totalAmount").value(25.00))
                .andExpect(jsonPath("$.deliveryAddress").value("学生宿舍 1 幢"))
                .andExpect(jsonPath("$.remark").doesNotExist())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].dishId").value(dish.getId()))
                .andExpect(jsonPath("$.items[0].dishName").value("鸡腿饭"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(12.50))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].subtotal").value(25.00));
        assertThat(dishRepository.findById(dish.getId()).orElseThrow().getStock()).isEqualTo(18);
    }

    @Test
    void listsOnSaleDishesAndReturnsEmptyListForUnknownMerchant() throws Exception {
        mvc.perform(get("/api/dishes").param("merchantId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(dish.getId()))
                .andExpect(jsonPath("$[0].name").value("鸡腿饭"))
                .andExpect(jsonPath("$[0].price").value(12.50))
                .andExpect(jsonPath("$[0].stock").value(20))
                .andExpect(jsonPath("$[0].status").value("ON_SALE"));
        mvc.perform(get("/api/dishes").param("merchantId", "999"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void listsOnlyTheRequestedStudentsOrderSummaries() throws Exception {
        String orderNo = createOrder("student-1");
        createOrder("student-2");
        mvc.perform(get("/api/orders").param("userId", "student-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderNo").value(orderNo))
                .andExpect(jsonPath("$[0].totalAmount").value(12.50))
                .andExpect(jsonPath("$[0].status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].items").doesNotExist());
        mvc.perform(get("/api/orders").param("userId", "unknown-student"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void requiresUserIdAndMerchantIdQueryParameters() throws Exception {
        for (String path : List.of("/api/orders", "/api/dishes")) {
            mvc.perform(get(path)).andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail").isNotEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ",
            "12345678901234567890123456789012345678901234567890123456789012345"})
    void rejectsInvalidUserIdQuery(String userId) throws Exception {
        mvc.perform(get("/api/orders").param("userId", userId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "0", "-1", "abc", "1.5", "9223372036854775808"})
    void rejectsInvalidMerchantIdQuery(String merchantId) throws Exception {
        mvc.perform(get("/api/dishes").param("merchantId", merchantId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    /** 同时覆盖嵌套校验、数量非整数、超出 Integer 范围和空明细。 */
    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "[null]", "[{}]",
            "[{\"dishId\":1,\"quantity\":0}]", "[{\"dishId\":1,\"quantity\":-1}]",
            "[{\"dishId\":0,\"quantity\":1}]", "[{\"dishId\":-1,\"quantity\":1}]",
            "[{\"dishId\":1,\"quantity\":1.5}]", "[{\"dishId\":1,\"quantity\":2147483648}]",
            "[{\"dishId\":1,\"quantity\":null}]", "[{\"dishId\":null,\"quantity\":1}]"})
    void rejectsInvalidItems(String items) throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body(items)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").isNotEmpty());
        assertThat(orderRepository.count()).isZero();
        assertThat(orderItemRepository.count()).isZero();
        assertThat(dishRepository.findById(dish.getId()).orElseThrow().getStock()).isEqualTo(20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{",
            "{\"merchantId\":10,\"items\":[{\"dishId\":1,\"quantity\":1}],\"deliveryAddress\":\"宿舍\"}",
            "{\"userId\":\" \",\"merchantId\":10,\"items\":[{\"dishId\":1,\"quantity\":1}],\"deliveryAddress\":\"宿舍\"}",
            "{\"userId\":\"u\",\"items\":[{\"dishId\":1,\"quantity\":1}],\"deliveryAddress\":\"宿舍\"}",
            "{\"userId\":\"u\",\"merchantId\":0,\"items\":[{\"dishId\":1,\"quantity\":1}],\"deliveryAddress\":\"宿舍\"}",
            "{\"userId\":\"u\",\"merchantId\":10,\"items\":[{\"dishId\":1,\"quantity\":1}]}",
            "{\"userId\":\"u\",\"merchantId\":10,\"items\":[{\"dishId\":1,\"quantity\":1}],\"deliveryAddress\":\" \"}"})
    void rejectsInvalidRequestBody(String body) throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void rejectsOverlongRequestFields() throws Exception {
        String valid = body("[{\"dishId\":" + dish.getId() + ",\"quantity\":1}]");
        for (String invalid : List.of(
                valid.replace("student-1", "u".repeat(65)),
                valid.replace("学生宿舍 1 幢", "址".repeat(256)),
                valid.replace("}", ",\"remark\":\"" + "注".repeat(501) + "\"}"))) {
            mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void returns404ForMissingDishAndOrder() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(body("[{\"dishId\":9223372036854775807,\"quantity\":1}]")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("菜品不存在：9223372036854775807"));
        mvc.perform(get("/api/orders/{orderNo}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"merchant", "offSale", "stock"})
    void returns409ForBusinessConflicts(String conflict) throws Exception {
        String reason = switch (conflict) {
            case "merchant" -> {
                dish.setMerchantId(11L);
                yield "菜品不属于请求商家：";
            }
            case "offSale" -> {
                dish.setStatus(DishStatus.OFF_SALE);
                yield "菜品已下架：";
            }
            default -> {
                dish.setStock(0);
                yield "菜品库存不足：";
            }
        };
        dishRepository.saveAndFlush(dish);
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(body("[{\"dishId\":" + dish.getId() + ",\"quantity\":1}]")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value(reason + dish.getId()));
        assertThat(orderRepository.count()).isZero();
        assertThat(orderItemRepository.count()).isZero();
    }

    private String createOrder(String userId) {
        return orderService.createOrder(new OrderDtos.Request(userId, 10L,
                List.of(new OrderDtos.ItemRequest(dish.getId(), 1)), "学生宿舍 1 幢", null)).orderNo();
    }

    private String body(String items) {
        return """
                {"userId":"student-1","merchantId":10,"items":%s,"deliveryAddress":"学生宿舍 1 幢"}
                """.formatted(items);
    }
}
