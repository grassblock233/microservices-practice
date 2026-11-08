package com.zjgsu.lht.client;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(ClientProperties.class)
public class HttpClientConfiguration {
    @Bean
    RestTemplate restTemplate(ClientProperties p) {
        if (p.connectTimeout() == null || p.connectTimeout().toMillis() < 1 ||
                p.readTimeout() == null || p.readTimeout().toMillis() < 1)
            throw new IllegalArgumentException("HTTP timeouts must be positive");
        var client = HttpClient.newBuilder().connectTimeout(p.connectTimeout()).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(p.readTimeout());
        return new RestTemplate(factory);
    }
}
