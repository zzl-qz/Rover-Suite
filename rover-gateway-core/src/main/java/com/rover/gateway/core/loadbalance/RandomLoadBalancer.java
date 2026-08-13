package com.rover.gateway.core.loadbalance;

import com.rover.common.model.ServiceInstance;
import com.rover.common.spi.loadbalance.LoadBalanceContext;
import com.rover.common.spi.loadbalance.LoadBalancer;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:22:00
 * Description: 随机负载均衡
 */
public class RandomLoadBalancer implements LoadBalancer {

    @Override
    public String name() {
        return "random";
    }

    @Override
    public ServiceInstance choose(LoadBalanceContext context) {
        if (context == null || !context.hasInstances()) {
            return null;
        }
        List<ServiceInstance> instances = context.getInstances();
        return instances.get(ThreadLocalRandom.current().nextInt(instances.size()));
    }
}
