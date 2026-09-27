package com.rover.gateway.core.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.model.ServiceInstance;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Author: Daylight
 * Created: 2026-09-27 15:40:00
 * Description: Nameserver 服务发现取实例契约：只返回健康实例，全不健康返回空（严格 503 的依据）
 *
 * <p>刻意验证「不做全不健康就退回全部」的兜底：那种兜底会把不可接流的实例静默放行，
 * 让「没得打」和「打得不好」在指标上无法区分。
 */
class NameserverServiceDiscoveryTest {

    @Test
    void allUnhealthyReturnsEmptyList() {
        NameserverServiceDiscovery discovery = new NameserverServiceDiscovery(new DiscoverySettings());
        discovery.getInstanceCache().putSnapshotFromQuery("demo", "v1", "epoch-1", 1L,
                List.of(instance("a", false), instance("b", false)));

        List<ServiceInstance> instances = discovery.getInstances("demo", "v1");
        assertTrue(instances.isEmpty(),
                "全部实例不健康时必须返回空列表，由上层明确给出 503，而不是退回全部缓存；实际=" + instances.size());
    }

    @Test
    void onlyHealthyInstancesAreReturned() {
        NameserverServiceDiscovery discovery = new NameserverServiceDiscovery(new DiscoverySettings());
        discovery.getInstanceCache().putSnapshotFromQuery("demo", "v1", "epoch-1", 1L,
                List.of(instance("a", true), instance("b", false), instance("c", true)));

        List<ServiceInstance> instances = discovery.getInstances("demo", "v1");
        Set<String> ids = instances.stream().map(ServiceInstance::getInstanceId).collect(Collectors.toSet());
        assertEquals(Set.of("a", "c"), ids, "只应返回健康实例，实际=" + ids);
    }

    /** 造一个 demo@v1 的实例，只关心 instanceId 与健康位。 */
    private static ServiceInstance instance(String instanceId, boolean healthy) {
        ServiceInstance instance = new ServiceInstance();
        instance.setServiceName("demo");
        instance.setGroup("v1");
        instance.setInstanceId(instanceId);
        instance.setHost("127.0.0.1");
        instance.setPort(9100);
        instance.setHealthy(healthy);
        return instance;
    }
}
