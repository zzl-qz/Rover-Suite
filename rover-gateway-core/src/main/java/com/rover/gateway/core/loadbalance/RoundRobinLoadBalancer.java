package com.rover.gateway.core.loadbalance;

import com.rover.common.model.ServiceInstance;
import com.rover.common.spi.loadbalance.LoadBalanceContext;
import com.rover.common.spi.loadbalance.LoadBalancer;
import com.rover.common.spi.loadbalance.BuiltinLoadBalanceStrategy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Author: Daylight
 * Created: 2026-08-07 16:40:00
 * Description: 轮询负载均衡，默认策略，按集群键独立计数
 */
public class RoundRobinLoadBalancer implements LoadBalancer {

    private static final int MAX_COUNTER_KEYS = 4096;

    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return BuiltinLoadBalanceStrategy.ROUND_ROBIN.configName();
    }

    @Override
    public ServiceInstance choose(LoadBalanceContext context) {
        if (context == null || !context.hasInstances()) {
            return null;
        }
        List<ServiceInstance> instances = context.getInstances();
        String key = context.getClusterKey() == null ? "default" : context.getClusterKey();
        if (!counters.containsKey(key) && counters.size() >= MAX_COUNTER_KEYS) {
            counters.clear();
        }
        AtomicInteger counter = counters.computeIfAbsent(key, ignored -> new AtomicInteger());
        int index = Math.floorMod(counter.getAndIncrement(), instances.size());
        return instances.get(index);
    }
}
