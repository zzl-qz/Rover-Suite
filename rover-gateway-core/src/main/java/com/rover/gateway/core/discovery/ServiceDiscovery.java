package com.rover.gateway.core.discovery;

import com.rover.common.model.ServiceInstance;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:20:00
 * Description: 上游服务实例发现
 */
public interface ServiceDiscovery extends AutoCloseable {

    void start();

    List<ServiceInstance> getInstances(String serviceName, String group);

    /** 路由热更新后补订新服务；静态发现默认空操作 */
    default void ensureWatch(String serviceName, String group) {
    }

    @Override
    void close();
}
