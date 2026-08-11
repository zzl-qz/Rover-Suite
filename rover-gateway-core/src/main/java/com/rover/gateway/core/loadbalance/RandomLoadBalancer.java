package com.rover.gateway.core.loadbalance;

import com.rover.common.model.ServiceInstance;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:40:00
 * Description: 随机挑一个实例
 *
 * 这个类是什么：LoadBalancer 的随机实现，均匀随机选实例。
 * 核心职责：instances 非空时 ThreadLocalRandom 随机取下标返回对应实例。
 * 被谁用：GatewayRuntime 在 loadbalance.strategy=random 时使用。
 */
public class RandomLoadBalancer implements LoadBalancer {

    /**
     * 从实例列表中随机选一个。
     *
     * @param serviceName 服务名（本实现未使用，保留接口一致性）
     * @param instances   候选实例列表
     * @return 随机选中的实例；instances 为空或 null 时 null
     */
    @Override
    public ServiceInstance choose(String serviceName, List<ServiceInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            return null;
        }
        return instances.get(ThreadLocalRandom.current().nextInt(instances.size()));
    }
}
