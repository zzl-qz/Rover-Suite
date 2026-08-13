package com.rover.gateway.core.loadbalance;

import com.rover.common.model.ServiceInstance;
import com.rover.common.spi.loadbalance.LoadBalanceContext;
import com.rover.common.spi.loadbalance.LoadBalancer;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-13
 * Description: 按客户端 IP 哈希，同 IP 尽量打到同一台
 */
public class IpHashLoadBalancer implements LoadBalancer {

    @Override
    public String name() {
        return "ip_hash";
    }

    @Override
    public ServiceInstance choose(LoadBalanceContext context) {
        if (context == null || !context.hasInstances()) {
            return null;
        }
        List<ServiceInstance> instances = context.getInstances();
        String ip = context.getClientIp();
        if (ip == null || ip.isBlank()) {
            // 拿不到 IP 时退化为稳定下标 0，避免 NPE
            return instances.get(0);
        }
        int index = Math.floorMod(ip.hashCode(), instances.size());
        return instances.get(index);
    }
}
