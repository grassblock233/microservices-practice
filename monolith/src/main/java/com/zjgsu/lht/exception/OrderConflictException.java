package com.zjgsu.lht.exception;

/** 请求格式合法，但菜品当前状态或库存不允许创建订单。 */
public class OrderConflictException extends RuntimeException {

    public OrderConflictException(String message) {
        super(message);
    }
}
