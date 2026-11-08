package com.zjgsu.lht.client;

import java.io.IOException;

import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

@Component
public class ServiceLocator {
    private final DiscoveryClient discovery;

    public ServiceLocator(DiscoveryClient discovery) {
        this.discovery = discovery;
    }

    public String baseUrl(String serviceName) {
        try {
            var instances = discovery.getInstances(serviceName);
            if (instances.isEmpty()) {
                throw new ResourceAccessException("服务暂无可用实例: " + serviceName);
            }
            return instances.getFirst().getUri().toString();
        } catch (ResourceAccessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ResourceAccessException("服务发现失败: " + serviceName, new IOException(ex));
        }
    }
}
