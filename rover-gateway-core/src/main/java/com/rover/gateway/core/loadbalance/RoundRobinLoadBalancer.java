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
 *
 * 这个类是什么：LoadBalancer 的轮询实现，每个 serviceName 独立计数。
 * 核心职责：按 serviceName 维护 AtomicInteger，getAndIncrement 后对实例数取模选实例。
 * 被谁用：GatewayRuntime 默认策略 round_robin；配置热更新时可重建实例。
 */
public class RoundRobinLoadBalancer implements LoadBalancer {

    /** 每个 serviceName 对应一个轮询计数器。 */
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /**
     * 按轮询策略从实例列表中选择一个目标实例。
     *
     * @param serviceName 服务名，用于隔离计数器
     * @param instances   候选实例列表
     * @return 选中的实例；serviceName 或 instances 无效时 null
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
