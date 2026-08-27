package com.rover.gateway.adapter.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.rover.common.model.ServiceInstance;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.discovery.ServiceDiscoveryFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NacosServiceDiscoveryTest {

    /** 工厂可被 ServiceLoader 使用，且创建阶段不应连接 Nacos。 */
    @Test
    void factoryAdvertisesNacosTypeWithoutStartingClient() {
        NacosServiceDiscoveryFactory factory = new NacosServiceDiscoveryFactory();
        assertEquals(DiscoveryType.NACOS, factory.type());
        factory.create(new DiscoverySettings()).close();
    }

    @Test
    void serviceLoaderFindsNacosFactory() {
        boolean found = false;
        for (ServiceDiscoveryFactory factory : ServiceLoader.load(ServiceDiscoveryFactory.class)) {
            if (factory.type() == DiscoveryType.NACOS) {
                found = true;
                break;
            }
        }
        assertTrue(found);
    }

    /** Nacos 实例变更后应转换为 Gateway 统一模型，并以快照形式提供读取。 */
    @Test
    void mapsInstancesIntoGatewaySnapshot() {
        FakeNacosClient fake = new FakeNacosClient();
        fake.put("orders", "DEFAULT_GROUP", instance("10.0.0.1", 8080, true, 25, "zone-a"));
        NacosServiceDiscovery discovery = discovery(fake);

        discovery.ensureWatch("orders", null);
        discovery.start();

        ServiceInstance instance = discovery.getInstances("orders", null).get(0);
        assertEquals("orders", instance.getServiceName());
        assertEquals("10.0.0.1", instance.getHost());
        assertEquals(8080, instance.getPort());
        assertEquals(25, instance.getWeight());
        assertEquals("zone-a", instance.getZone());
        assertTrue(instance.isHealthy());
        assertEquals(1, fake.subscribeCount.get());
        discovery.close();
        assertTrue(fake.closed);
    }

    @Test
    void prefersHealthyInstancesAndFallsBackWhenAllUnhealthy() {
        FakeNacosClient fake = new FakeNacosClient();
        fake.put(
                "orders",
                "DEFAULT_GROUP",
                instance("10.0.0.1", 8080, true, 1, "a"),
                instance("10.0.0.2", 8080, false, 1, "b"));
        NacosServiceDiscovery discovery = discovery(fake);
        discovery.ensureWatch("orders", null);
        discovery.start();

        List<ServiceInstance> healthy = discovery.getInstances("orders", null);
        assertEquals(1, healthy.size());
        assertEquals("10.0.0.1", healthy.get(0).getHost());

        fake.push("orders", "DEFAULT_GROUP", instance("10.0.0.2", 8080, false, 1, "b"));
        List<ServiceInstance> fallback = discovery.getInstances("orders", null);
        assertEquals(1, fallback.size());
        assertEquals("10.0.0.2", fallback.get(0).getHost());
        discovery.close();
    }

    @Test
    void namingEventReplacesSnapshot() {
        FakeNacosClient fake = new FakeNacosClient();
        fake.put("orders", "DEFAULT_GROUP", instance("10.0.0.1", 8080, true, 1, "a"));
        NacosServiceDiscovery discovery = discovery(fake);
        discovery.ensureWatch("orders", null);
        discovery.start();

        fake.push("orders", "DEFAULT_GROUP", instance("10.0.0.9", 9090, true, 8, "b"));
        ServiceInstance instance = discovery.getInstances("orders", null).get(0);
        assertEquals("10.0.0.9", instance.getHost());
        assertEquals(9090, instance.getPort());
        discovery.close();
    }

    @Test
    void ensureWatchDoesNotSubscribeTwice() {
        FakeNacosClient fake = new FakeNacosClient();
        fake.put("orders", "DEFAULT_GROUP", instance("10.0.0.1", 8080, true, 1, "a"));
        NacosServiceDiscovery discovery = discovery(fake);
        discovery.ensureWatch("orders", null);
        discovery.start();
        discovery.ensureWatch("orders", null);
        discovery.ensureWatch("orders", "");
        assertEquals(1, fake.subscribeCount.get());
        discovery.close();
    }

    @Test
    void startFailsWhenServerAddrMissing() {
        DiscoverySettings settings = new DiscoverySettings();
        NacosServiceDiscovery discovery = new NacosServiceDiscovery(settings);
        IllegalStateException error = assertThrows(IllegalStateException.class, discovery::start);
        assertTrue(error.getMessage().contains("Nacos"));
    }

    private static NacosServiceDiscovery discovery(FakeNacosClient fake) {
        DiscoverySettings settings = new DiscoverySettings();
        settings.setProviderProperties(Map.of("serverAddr", "test:8848"));
        return new NacosServiceDiscovery(settings, ignored -> fake);
    }

    private static Instance instance(String ip, int port, boolean healthy, double weight, String cluster) {
        Instance instance = new Instance();
        instance.setServiceName("DEFAULT_GROUP@@orders");
        instance.setIp(ip);
        instance.setPort(port);
        instance.setWeight(weight);
        instance.setHealthy(healthy);
        instance.setEnabled(true);
        instance.setClusterName(cluster);
        instance.setMetadata(Map.of("version", "v1"));
        return instance;
    }

    private static final class FakeNacosClient implements NacosClient {
        private final Map<String, List<Instance>> data = new ConcurrentHashMap<>();
        private final Map<String, EventListener> listeners = new ConcurrentHashMap<>();
        private final AtomicInteger subscribeCount = new AtomicInteger();
        private boolean closed;

        void put(String serviceName, String group, Instance... instances) {
            data.put(key(serviceName, group), List.of(instances));
        }

        void push(String serviceName, String group, Instance... instances) {
            put(serviceName, group, instances);
            EventListener listener = listeners.get(key(serviceName, group));
            if (listener != null) {
                listener.onEvent(new NamingEvent(serviceName, group, "", List.of(instances)));
            }
        }

        @Override
        public void subscribe(String serviceName, String group, EventListener listener) {
            subscribeCount.incrementAndGet();
            listeners.put(key(serviceName, group), listener);
        }

        @Override
        public List<Instance> getAllInstances(String serviceName, String group) {
            return new ArrayList<>(data.getOrDefault(key(serviceName, group), List.of()));
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

        private static String key(String serviceName, String group) {
            return serviceName + "|" + group;
        }
    }
}
