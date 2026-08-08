package com.rover.gateway.core.loadbalance;

import com.rover.common.model.ServiceInstance;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:22:00
 * Description: 定义 Gateway 选择后端服务实例的负载均衡契约
 */
public interface LoadBalancer {

    /**
     * 从服务实例列表中选择一个目标实例。
     */
    ServiceInstance choose(String serviceName, List<ServiceInstance> instances);
}
