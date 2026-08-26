package com.rover.gateway.adapter.nacos;

import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.rover.common.model.ServiceInstance;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NacosServiceDiscoveryTest {

    /** 工厂可被 ServiceLoader 使用，且创建阶段不应连接 Nacos。 */
    @Test
    void factoryAdvertisesNacosTypeWithoutStartingClient() {
        NacosServiceDiscoveryFactory factory = new NacosServiceDiscoveryFactory();
        assertEquals(DiscoveryType.NACOS, factory.type());
        factory.create(new DiscoverySettings()).close();
    }

    /** Nacos 实例变更后应转换为 Gateway 统一模型，并以快照形式提供读取。 */
    @Test
    void mapsInstancesIntoGatewaySnapshot() {
        DiscoverySettings settings = new DiscoverySettings();
        settings.setProviderProperties(Map.of("serverAddr", "test:8848"));
        FakeNacosClient fake = new FakeNacosClient();
        NacosServiceDiscovery discovery = new NacosServiceDiscovery(settings, ignored -> fake);

        discovery.ensureWatch("orders", null);
        discovery.start();

        ServiceInstance instance = discovery.getInstances("orders", null).get(0);
        assertEquals("10.0.0.1", instance.getHost());
        assertEquals(8080, instance.getPort());
        assertEquals(25, instance.getWeight());
        assertEquals("zone-a", instance.getZone());
        assertTrue(instance.isHealthy());
        discovery.close();
        assertTrue(fake.closed);
    }

    private static final class FakeNacosClient implements NacosClient {
        private boolean closed;

        @Override
        public void subscribe(String serviceName, String group, EventListener listener) {
        }

        @Override
        public List<Instance> getAllInstances(String serviceName, String group) {
            Instance instance = new Instance();
            instance.setServiceName(serviceName);
            instance.setIp("10.0.0.1");
            instance.setPort(8080);
            instance.setWeight(25);
            instance.setHealthy(true);
            instance.setEnabled(true);
            instance.setClusterName("zone-a");
            instance.setMetadata(Map.of("version", "v1"));
            return List.of(instance);
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void unsubscribe(String serviceName, String group, EventListener listener) {
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
