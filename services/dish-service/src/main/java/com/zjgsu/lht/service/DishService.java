package com.zjgsu.lht.service;

import com.zjgsu.lht.dto.DishDtos;
import com.zjgsu.lht.model.DishStatus;
import com.zjgsu.lht.repository.DishRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class DishService {

    private final DishRepository dishRepository;

    public DishService(DishRepository dishRepository) {
        this.dishRepository = dishRepository;
    }

    /**
     * 按菜品编号升序查询指定商家的上架菜品
     */
    public List<DishDtos.Response> getDishes(Long merchantId) {
        return dishRepository.findByMerchantIdAndStatusOrderByIdAsc(merchantId, DishStatus.ON_SALE)
                .stream()
                .map(dish -> new DishDtos.Response(dish.getId(), dish.getMerchantId(), dish.getName(),
                        dish.getCategory(), dish.getPrice(), dish.getStock(), dish.getStatus()))
                .toList();
    }
}
