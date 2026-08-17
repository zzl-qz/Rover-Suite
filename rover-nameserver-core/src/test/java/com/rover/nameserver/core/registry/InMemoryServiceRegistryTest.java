package com.rover.nameserver.core.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.protocol.RegisterRequest;
import com.rover.nameserver.core.model.InstanceRecord;
import com.rover.nameserver.core.registration.RegistrationOwner;
import com.rover.nameserver.core.registration.RegistrationResult;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InMemoryServiceRegistryTest {

    private static final RegistrationOwner OWNER = RegistrationOwner.tcp("test-channel");

    @Test
    void concurrentLastUnregisterNeverDropsNewRegistration() throws Exception {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 500; i++) {
                String service = "svc-" + i;
                registry.register(request(service, "old", true), OWNER);
                CountDownLatch start = new CountDownLatch(1);
                var remove = pool.submit(() -> {
                    await(start);
                    registry.unregister(service, "old", OWNER);
                });
                var add = pool.submit(() -> {
                    await(start);
                    registry.register(request(service, "new", true), OWNER);
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
        registry.register(request("svc", "one", false), OWNER);
        InstanceRecord record = registry.listAllRecords().get(0);

        record.setLastHeartbeatMillis(1);
        registry.heartbeat("svc", "one", OWNER);
        assertNull(registry.markUnhealthy("svc", "one", 1));
        assertTrue(registry.query("svc", null, false).get(0).isHealthy());

        record.setLastHeartbeatMillis(1);
        RegistrySnapshot unhealthy = registry.markUnhealthy("svc", "one", System.currentTimeMillis());
        assertNotNull(unhealthy);
        assertFalse(unhealthy.getInstances().get(0).isHealthy());
        RegistrationResult recovered = registry.heartbeat("svc", "one", OWNER);
        assertNotNull(recovered.snapshot());
        assertTrue(recovered.snapshot().getInstances().get(0).isHealthy());
    }

    @Test
    void sameOwnerSameRegistrationOnlyRenewsLease() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        RegisterRequest request = request("svc", "one", true);

        RegistrationResult first = registry.register(request, OWNER);
        long firstHeartbeat = registry.listAllRecords().get(0).getLastHeartbeatMillis();
        RegistrationResult retry = registry.register(request, OWNER);

        assertTrue(first.isChanged());
        assertEquals(RegistrationResult.Status.UNCHANGED, retry.status());
        assertEquals(first.revision(), retry.revision());
        assertEquals(first.revision(), registry.revisionOf("svc"));
        assertTrue(registry.listAllRecords().get(0).getLastHeartbeatMillis() >= firstHeartbeat);
    }

    @Test
    void newOwnerTakesOverAndOldOwnerCannotRenewOrRemove() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        RegistrationOwner oldOwner = RegistrationOwner.tcp("old-channel");
        RegistrationOwner newOwner = RegistrationOwner.http("new-session");
        RegisterRequest request = request("svc", "one", true);

        RegistrationResult first = registry.register(request, oldOwner);
        RegistrationResult takeover = registry.register(request, newOwner);
        RegistrationResult staleHeartbeat = registry.heartbeat("svc", "one", oldOwner);
        RegistrationResult staleUnregister = registry.unregister("svc", "one", oldOwner);

        assertTrue(first.isChanged());
        assertTrue(takeover.isChanged());
        assertEquals(first.revision() + 1, takeover.revision());
        assertTrue(staleHeartbeat.isOwnerMismatch());
        assertTrue(staleUnregister.isOwnerMismatch());
        assertEquals(1, registry.query("svc", null, false).size());
        assertEquals(newOwner, registry.listAllRecords().get(0).getOwner());
    }

    @Test
    void staleExpirationScanCannotRemoveNewSession() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        RegistrationOwner oldOwner = RegistrationOwner.tcp("old-channel");
        RegistrationOwner newOwner = RegistrationOwner.tcp("new-channel");
        RegisterRequest request = request("svc", "one", true);
        registry.register(request, oldOwner);
        InstanceRecord staleRecord = registry.listAllRecords().get(0);
        staleRecord.setLastHeartbeatMillis(1);

        registry.register(request, newOwner);
        RegistrySnapshot removed = registry.removeExpired("svc", "one", oldOwner, Long.MAX_VALUE);

        assertNull(removed);
        assertEquals(1, registry.query("svc", null, false).size());
        assertEquals(newOwner, registry.listAllRecords().get(0).getOwner());
    }

    @Test
    void staleExpirationScanCannotRemoveRenewedSameSession() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        RegisterRequest request = request("svc", "one", true);
        registry.register(request, OWNER);
        InstanceRecord staleRecord = registry.listAllRecords().get(0);
        staleRecord.setLastHeartbeatMillis(1);

        registry.heartbeat("svc", "one", OWNER);
        RegistrySnapshot removed = registry.removeExpired("svc", "one", OWNER, 1);

        assertNull(removed);
        assertEquals(1, registry.query("svc", null, false).size());
        assertEquals(OWNER, registry.listAllRecords().get(0).getOwner());
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
