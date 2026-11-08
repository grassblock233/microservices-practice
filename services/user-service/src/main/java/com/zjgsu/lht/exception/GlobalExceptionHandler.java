package com.zjgsu.lht.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail business(ApiException e) {
        var p = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(e.status), e.getMessage());
        p.setProperty("code", e.code);
        if (e.orderNo != null) p.setProperty("orderNo", e.orderNo);
        return p;
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body,
                                                             HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var p = ProblemDetail.forStatusAndDetail(status,
                status.is4xxClientError() ? "请求参数或 JSON 格式不合法" : "服务处理失败");
        p.setProperty("code", status.value() == 400 ? "INVALID_REQUEST" : "REQUEST_FAILED");
        return super.handleExceptionInternal(e, p, headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e) {
        log.error("event=unexpected_failure", e);
        var p = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "服务处理失败");
        p.setProperty("code", "INTERNAL_ERROR");
        return p;
    }
}
