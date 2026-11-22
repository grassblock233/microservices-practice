package com.zjgsu.lht.client;

import com.zjgsu.lht.exception.ApiException;
import feign.FeignException;
import feign.RetryableException;
import feign.codec.DecodeException;
import org.springframework.stereotype.Component;
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

    public <T> T call(String service, Supplier<T> action, Map<String, Integer> allowed) {
        try {
            return action.get();
        } catch (RetryableException e) {
            boolean timeout = false;
            for (Throwable c = e; c != null; c = c.getCause())
                if (c instanceof SocketTimeoutException || c instanceof HttpTimeoutException) timeout = true;
            throw new ApiException(timeout ? 504 : 503, timeout ? "DEPENDENCY_TIMEOUT" : "DEPENDENCY_UNAVAILABLE",
                    service + (timeout ? " 请求超时" : " 暂无可用连接"), null, e);
        } catch (DecodeException e) {
            throw new ApiException(502, "DEPENDENCY_BAD_RESPONSE", service + " 响应格式不正确", null, e);
        } catch (FeignException e) {
            String code = "";
            try {
                code = json.readTree(e.contentUTF8()).path("code").asText();
            } catch (RuntimeException ignored) { /* Never propagate an untrusted response body. */ }
            if (allowed.getOrDefault(code, 0) == e.status())
                throw new ApiException(e.status(), code, service + " 拒绝业务请求（" + code + "）", null, e);
            if (e.status() >= 500 || e.status() < 0)
                throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", service + " 暂不可用", null, e);
            throw new ApiException(502, "DEPENDENCY_BAD_RESPONSE", service + " 返回异常响应", null, e);
        } catch (RuntimeException e) {
            // Discovery/instance selection can fail before an HTTP response exists.
            throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", service + " 服务发现或实例选择失败", null, e);
        }
    }

    public static ApiException badResponse() {
        return new ApiException(502, "DEPENDENCY_BAD_RESPONSE", "依赖服务响应结构不合法");
    }
}
