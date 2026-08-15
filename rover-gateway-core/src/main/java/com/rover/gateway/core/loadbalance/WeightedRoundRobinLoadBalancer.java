package com.rover.gateway.core.loadbalance;

import com.rover.common.model.ServiceInstance;
import com.rover.common.spi.loadbalance.LoadBalanceContext;
import com.rover.common.spi.loadbalance.LoadBalancer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Author: Daylight
 * Created: 2026-08-05 11:20:00
 * Description: 加权轮询负载均衡，按 ServiceInstance.weight 展开实例后轮询
 */
public class WeightedRoundRobinLoadBalancer implements LoadBalancer {

    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "weighted_round_robin";
    }

    @Override
    public ServiceInstance choose(LoadBalanceContext context) {
        if (context == null || !context.hasInstances()) {
            return null;
        }
        List<ServiceInstance> expanded = expandByWeight(context.getInstances());
        if (expanded.isEmpty()) {
            return null;
        }
        String key = context.getClusterKey() == null ? "default" : context.getClusterKey();
        AtomicInteger counter = counters.computeIfAbsent(key, ignored -> new AtomicInteger());
        int index = Math.floorMod(counter.getAndIncrement(), expanded.size());
        return expanded.get(index);
    }

    private static List<ServiceInstance> expandByWeight(List<ServiceInstance> instances) {
        List<ServiceInstance> expanded = new ArrayList<>();
        for (ServiceInstance instance : instances) {
            int weight = instance.getWeight() <= 0 ? 1 : instance.getWeight();
            // 防止配置夸张权重把内存打爆，单实例最多扩 1000 份
            int copies = Math.min(weight, 1000);
            for (int i = 0; i < copies; i++) {
                expanded.add(instance);
            }
        }
        return expanded;
    }
}
