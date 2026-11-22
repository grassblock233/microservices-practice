package com.zjgsu.lht.exception;

public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public ApiException(int status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }
}
