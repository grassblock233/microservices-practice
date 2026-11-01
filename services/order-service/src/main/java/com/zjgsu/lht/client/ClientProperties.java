package com.zjgsu.lht.client;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
@ConfigurationProperties(prefix="clients")
public record ClientProperties(Endpoint user, Endpoint dish, Duration connectTimeout, Duration readTimeout) {
    public record Endpoint(String baseUrl) {}
}
