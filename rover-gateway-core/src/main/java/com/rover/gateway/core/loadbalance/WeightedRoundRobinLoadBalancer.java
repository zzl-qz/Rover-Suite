package com.rover.gateway.core.loadbalance;

import com.rover.common.model.ServiceInstance;
import com.rover.common.spi.loadbalance.LoadBalanceContext;
import com.rover.common.spi.loadbalance.LoadBalancer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Author: Daylight
 * Created: 2026-08-05 11:20:00
 * Description: 加权轮询负载均衡，按累计权重直接定位，不为每次请求展开临时列表
 */
public class WeightedRoundRobinLoadBalancer implements LoadBalancer {

    /** 单实例按权重展开的份数上限，防止配置夸张权重把内存打爆 */
    private static final int MAX_EXPAND_COPIES = 1000;
    private static final int MAX_COUNTER_KEYS = 4096;

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "weighted_round_robin";
    }

    @Override
    public ServiceInstance choose(LoadBalanceContext context) {
        if (context == null || !context.hasInstances()) {
            return null;
        }
        List<ServiceInstance> instances = context.getInstances();
        long totalWeight = 0;
        for (ServiceInstance instance : instances) {
            totalWeight += normalizedWeight(instance);
        }
        String key = context.getClusterKey() == null ? "default" : context.getClusterKey();
        if (!counters.containsKey(key) && counters.size() >= MAX_COUNTER_KEYS) {
            counters.clear();
        }
        AtomicLong counter = counters.computeIfAbsent(key, ignored -> new AtomicLong());
        long slot = Math.floorMod(counter.getAndIncrement(), totalWeight);
        long cumulative = 0;
        for (ServiceInstance instance : instances) {
            cumulative += normalizedWeight(instance);
            if (slot < cumulative) {
                return instance;
            }
        }
        return instances.get(instances.size() - 1);
    }

    private static int normalizedWeight(ServiceInstance instance) {
        int weight = instance.getWeight() <= 0 ? 1 : instance.getWeight();
        return Math.min(weight, MAX_EXPAND_COPIES);
    }
}
