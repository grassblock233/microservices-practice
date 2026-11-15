package com.zjgsu.lht.client;

import feign.Retryer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpClientConfiguration {
    @Bean
    Retryer feignRetryer() {
        // A timed-out write may have committed. Recovery uses the existing orderNo.
        return Retryer.NEVER_RETRY;
    }
}
