package com.rover.gateway.core.discovery;

import com.rover.common.model.ServiceInstance;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:20:00
 * Description: 静态模式占位，不连注册中心
 */
public class NoopServiceDiscovery implements ServiceDiscovery {

    @Override
    public void start() {
        // static 模式什么都不做
    }

    @Override
    public List<ServiceInstance> getInstances(String serviceName, String group) {
        return List.of();
    }

    @Override
    public void close() {
        // no-op
    }
}
