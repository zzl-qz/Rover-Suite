package com.rover.gateway.core.loadbalance;

import com.rover.common.model.ServiceInstance;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:40:00
 * Description: 随机挑一个实例
 */
public class RandomLoadBalancer implements LoadBalancer {

    @Override
    public ServiceInstance choose(String serviceName, List<ServiceInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            return null;
        }
        return instances.get(ThreadLocalRandom.current().nextInt(instances.size()));
    }
}
