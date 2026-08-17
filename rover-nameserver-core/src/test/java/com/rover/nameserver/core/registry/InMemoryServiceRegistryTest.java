package com.rover.nameserver.core.registry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.protocol.RegisterRequest;
import com.rover.nameserver.core.model.InstanceRecord;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InMemoryServiceRegistryTest {

    @Test
    void concurrentLastUnregisterNeverDropsNewRegistration() throws Exception {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 500; i++) {
                String service = "svc-" + i;
                registry.register(request(service, "old", true));
                CountDownLatch start = new CountDownLatch(1);
                var remove = pool.submit(() -> {
                    await(start);
                    registry.unregister(service, "old");
                });
                var add = pool.submit(() -> {
                    await(start);
                    registry.register(request(service, "new", true));
                });
                start.countDown();
                remove.get(2, TimeUnit.SECONDS);
                add.get(2, TimeUnit.SECONDS);
                assertTrue(registry.query(service, null, false).stream()
                        .anyMatch(instance -> "new".equals(instance.getInstanceId())));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void freshHeartbeatPreventsStaleHealthCheckFromMarkingUnhealthy() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        registry.register(request("svc", "one", false));
        InstanceRecord record = registry.listAllRecords().get(0);

        record.setLastHeartbeatMillis(1);
        registry.heartbeat("svc", "one");
        assertNull(registry.markUnhealthy("svc", "one", 1));
        assertTrue(registry.query("svc", null, false).get(0).isHealthy());

        record.setLastHeartbeatMillis(1);
        RegistrySnapshot unhealthy = registry.markUnhealthy("svc", "one", System.currentTimeMillis());
        assertNotNull(unhealthy);
        assertFalse(unhealthy.getInstances().get(0).isHealthy());
        HeartbeatResult recovered = registry.heartbeat("svc", "one");
        assertNotNull(recovered.healthRecoveredSnapshot());
        assertTrue(recovered.healthRecoveredSnapshot().getInstances().get(0).isHealthy());
    }

    private static RegisterRequest request(String service, String instanceId, boolean ephemeral) {
        RegisterRequest request = new RegisterRequest();
        request.setServiceName(service);
        request.setInstanceId(instanceId);
        request.setHost("127.0.0.1");
        request.setPort(8080);
        request.setEphemeral(ephemeral);
        return request;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
