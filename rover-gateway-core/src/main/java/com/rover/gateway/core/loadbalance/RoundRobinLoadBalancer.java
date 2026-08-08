/**
 * 作者：Daylight
 * 创建时间：2026-08-08 14:22:00
 * 描述：提供轮询负载均衡策略的骨架实现
 */
package com.rover.gateway.core.loadbalance;

import com.rover.common.model.ServiceInstance;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RoundRobinLoadBalancer implements LoadBalancer {

    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /**
     * 按轮询策略选择健康服务实例，当前用于静态配置路由的最小可用版本。
     */
    @Override
    public ServiceInstance choose(String serviceName, List<ServiceInstance> instances) {
        if (serviceName == null || instances == null || instances.isEmpty()) {
            return null;
        }
        AtomicInteger counter = counters.computeIfAbsent(serviceName, ignored -> new AtomicInteger());
        int index = Math.floorMod(counter.getAndIncrement(), instances.size());
        return instances.get(index);
    }
}
