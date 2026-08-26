package com.rover.gateway.core.loadbalance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.rover.common.model.ServiceInstance;
import com.rover.common.spi.loadbalance.LoadBalanceContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoundRobinLoadBalancerTest {

    @Test
    void fullCounterMapStillPicksWithoutResettingOldKeys() {
        RoundRobinLoadBalancer lb = new RoundRobinLoadBalancer();
        ServiceInstance a = instance("a");
        ServiceInstance b = instance("b");
        List<ServiceInstance> pair = List.of(a, b);

        ServiceInstance firstKeyFirstPick = lb.choose(LoadBalanceContext.of("cluster-0", pair));
        assertEquals(a.getHost(), firstKeyFirstPick.getHost());

        for (int i = 1; i < 4096; i++) {
            assertNotNull(lb.choose(LoadBalanceContext.of("cluster-" + i, pair)));
        }
        // 第 4097 个键走 overflow，不能把 cluster-0 的计数清零
        assertNotNull(lb.choose(LoadBalanceContext.of("overflow", pair)));
        assertEquals(b.getHost(), lb.choose(LoadBalanceContext.of("cluster-0", pair)).getHost());
    }

    private static ServiceInstance instance(String host) {
        ServiceInstance instance = new ServiceInstance();
        instance.setHost(host);
        instance.setPort(8080);
        instance.setHealthy(true);
        return instance;
    }
}
