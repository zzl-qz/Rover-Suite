package com.rover.gateway.core.discovery;

import com.rover.common.model.ServiceInstance;
import com.rover.common.spi.discovery.ServiceDiscovery;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-11 14:48:00
 * Description: 静态模式服务发现：不连注册中心，实例查询始终返回空
 */
public class NoopServiceDiscovery implements ServiceDiscovery {

    @Override
    public void start() {
    }

    @Override
    public List<ServiceInstance> getInstances(String serviceName, String group) {
        return List.of();
    }

    @Override
    public void close() {
    }
}
