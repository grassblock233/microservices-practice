package com.zjgsu.lht.client;

import com.zjgsu.lht.exception.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class DependencyCalls {
    private final ObjectMapper json;

    public DependencyCalls(ObjectMapper json) {
        this.json = json;
    }

    public <T> T call(Supplier<T> action, Map<String, Integer> allowed) {
        try {
            return action.get();
        } catch (RestClientResponseException e) {
            String code = "";
            try {
                code = json.readTree(e.getResponseBodyAsString()).path("code").asText();
            } catch (RuntimeException ignored) { /* Never propagate an untrusted response body. */ }
            if (allowed.getOrDefault(code, 0) == e.getStatusCode().value())
                throw new ApiException(e.getStatusCode().value(), code, "依赖服务拒绝业务请求");
            throw new ApiException(502, "DEPENDENCY_BAD_RESPONSE", "依赖服务返回异常响应", null, e);
        } catch (ResourceAccessException e) {
            boolean timeout = false;
            for (Throwable c = e; c != null; c = c.getCause())
                if (c instanceof SocketTimeoutException || c instanceof HttpTimeoutException) timeout = true;
            throw new ApiException(timeout ? 504 : 503, timeout ? "DEPENDENCY_TIMEOUT" : "DEPENDENCY_UNAVAILABLE",
                    timeout ? "依赖服务请求超时" : "依赖服务无法连接", null, e);
        } catch (RestClientException e) {
            throw new ApiException(502, "DEPENDENCY_BAD_RESPONSE", "依赖服务响应格式不正确", null, e);
        }
    }

    public static ApiException badResponse() {
        return new ApiException(502, "DEPENDENCY_BAD_RESPONSE", "依赖服务响应结构不合法");
    }
}
