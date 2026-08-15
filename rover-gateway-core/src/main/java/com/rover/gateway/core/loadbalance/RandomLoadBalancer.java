package com.rover.gateway.core.loadbalance;

import com.rover.common.model.ServiceInstance;
import com.rover.common.spi.loadbalance.LoadBalanceContext;
import com.rover.common.spi.loadbalance.LoadBalancer;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Author: Daylight
 * Created: 2026-08-06 15:26:00
 * Description: 随机负载均衡，从实例列表中均匀随机选取
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
