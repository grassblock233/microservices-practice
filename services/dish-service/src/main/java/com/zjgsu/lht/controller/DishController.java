package com.zjgsu.lht.controller;

import com.zjgsu.lht.dto.DishDtos;
import com.zjgsu.lht.service.DishService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dishes")
public class DishController {

    private final DishService dishService;

    public DishController(DishService dishService) {
        this.dishService = dishService;
    }

    /**
     * 查询指定商家的上架菜品，包含库存为零的售罄菜品。
     *
     * <p>请求示例：</p>
     * <pre>{@code
     * curl 'http://localhost:8080/api/dishes?merchantId=10'
     * }</pre>
     *
     * @param request 查询参数，包含商家编号 merchantId
     * @return 按菜品编号升序排列的上架菜品列表
     */
    @GetMapping
    public List<DishDtos.Response> getDishes(@Valid @ModelAttribute DishDtos.Request request) {
        return dishService.getDishes(request.merchantId());
    }
}
