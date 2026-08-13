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
 * Created: 2026-08-13
 * Description: 最少连接（按网关视角的在途请求数）
 *
 * 依赖 RouteAndProxyFilter 在转发前后调用 onStart / onComplete。
 */
public class LeastConnectionsLoadBalancer implements LoadBalancer {

    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "least_connections";
    }

    @Override
    public ServiceInstance choose(LoadBalanceContext context) {
        if (context == null || !context.hasInstances()) {
            return null;
        }
        List<ServiceInstance> instances = context.getInstances();
        ServiceInstance best = null;
        int bestCount = Integer.MAX_VALUE;
        for (ServiceInstance instance : instances) {
            int count = inFlight
                    .computeIfAbsent(instanceKey(instance), ignored -> new AtomicInteger())
                    .get();
            if (count < bestCount) {
                bestCount = count;
                best = instance;
            }
        }
        return best;
    }

    @Override
    public void onStart(ServiceInstance instance) {
        if (instance == null) {
            return;
        }
        inFlight.computeIfAbsent(instanceKey(instance), ignored -> new AtomicInteger()).incrementAndGet();
    }

    @Override
    public void onComplete(ServiceInstance instance) {
        if (instance == null) {
            return;
        }
        AtomicInteger counter = inFlight.get(instanceKey(instance));
        if (counter != null) {
            counter.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    private static String instanceKey(ServiceInstance instance) {
        if (instance.getInstanceId() != null && !instance.getInstanceId().isBlank()) {
            return instance.getInstanceId();
        }
        return instance.getHost() + ":" + instance.getPort();
    }
}
