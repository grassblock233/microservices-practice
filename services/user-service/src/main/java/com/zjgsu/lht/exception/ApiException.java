package com.zjgsu.lht.exception;

public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public final String orderNo;

    public ApiException(int status, String code, String detail) {
        this(status, code, detail, null, null);
    }

    public ApiException(int status, String code, String detail, String orderNo, Throwable cause) {
        super(detail, cause);
        this.status = status;
        this.code = code;
        this.orderNo = orderNo;
    }

    public ApiException forOrder(String orderNo) {
        return new ApiException(status, code, getMessage(), orderNo, this);
    }
}
