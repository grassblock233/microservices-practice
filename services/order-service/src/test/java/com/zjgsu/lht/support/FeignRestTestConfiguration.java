package com.zjgsu.lht.support;

import feign.Client;
import feign.Response;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the existing transaction/compensation scenarios and ordered HTTP assertions.
 * Only the lowest Feign transport is replaced; Feign contracts, codecs and LoadBalancer
 * still execute. FeignClientTests separately exercises the real HTTP transport.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FeignRestTestConfiguration {
    @Bean
    RestTemplate testTransport() {
        var http = new RestTemplate();
        // Feign, rather than RestTemplate, must decode non-2xx responses.
        http.setErrorHandler(new org.springframework.web.client.NoOpResponseErrorHandler());
        return http;
    }

    @Bean
    @Primary
    Client testFeignClient(RestTemplate http, LoadBalancerClient loadBalancer,
                           LoadBalancerClientFactory factory) {
        Client delegate = (request, options) -> {
            try {
                return http.execute(URI.create(request.url()), HttpMethod.valueOf(request.httpMethod().name()),
                        outgoing -> {
                            request.headers().forEach((key, values) -> outgoing.getHeaders().put(key, List.copyOf(values)));
                            if (request.body() != null) outgoing.getBody().write(request.body());
                        }, incoming -> {
                            Map<String, Collection<String>> headers = new LinkedHashMap<>();
                            incoming.getHeaders().forEach(headers::put);
                            return Response.builder().request(request).status(incoming.getStatusCode().value())
                                    .reason(incoming.getStatusText()).headers(headers)
                                    .body(StreamUtils.copyToByteArray(incoming.getBody())).build();
                        });
            } catch (ResourceAccessException error) {
                if (error.getCause() instanceof IOException io) throw io;
                throw new IOException(error);
            }
        };
        return new FeignBlockingLoadBalancerClient(delegate, loadBalancer, factory, List.of());
    }
}
