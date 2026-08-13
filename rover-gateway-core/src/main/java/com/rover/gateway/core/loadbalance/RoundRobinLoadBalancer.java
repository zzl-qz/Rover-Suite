package com.rover.gateway.core.loadbalance;

import com.rover.common.model.ServiceInstance;
import com.rover.common.spi.LoadBalanceContext;
import com.rover.common.spi.LoadBalancer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:22:00
 * Description: 轮询（默认策略）
 */
public class RoundRobinLoadBalancer implements LoadBalancer {

    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "round_robin";
    }

    @Override
    public ServiceInstance choose(LoadBalanceContext context) {
        if (context == null || !context.hasInstances()) {
            return null;
        }
        List<ServiceInstance> instances = context.getInstances();
        String key = context.getClusterKey() == null ? "default" : context.getClusterKey();
        AtomicInteger counter = counters.computeIfAbsent(key, ignored -> new AtomicInteger());
        int index = Math.floorMod(counter.getAndIncrement(), instances.size());
        return instances.get(index);
    }
}
