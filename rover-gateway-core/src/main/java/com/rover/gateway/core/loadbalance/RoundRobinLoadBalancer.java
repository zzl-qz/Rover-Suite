package com.rover.gateway.core.loadbalance;

import com.rover.common.model.ServiceInstance;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:22:00
 * Description: 按服务名维护计数器，实现轮询负载均衡
 */
public class RoundRobinLoadBalancer implements LoadBalancer {

    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /**
     * 按轮询策略从实例列表中选择一个目标实例。
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
