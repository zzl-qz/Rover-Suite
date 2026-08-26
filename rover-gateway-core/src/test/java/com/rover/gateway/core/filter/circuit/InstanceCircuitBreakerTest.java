package com.rover.gateway.core.filter.circuit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.model.ServiceInstance;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class InstanceCircuitBreakerTest {

    @Test
    void healthyListIsSameInstanceWhenNothingOpen() {
        CircuitBreakerSettings settings = settings(CircuitBreakerSettings.ALL);
        InstanceCircuitBreaker breaker = new InstanceCircuitBreaker(settings);
        List<ServiceInstance> instances = List.of(instance("10.0.0.1", 8080), instance("10.0.0.2", 8080));

        assertSame(instances, breaker.filterAvailable(instances));
        assertEquals(0, breaker.openCount());
    }

    @Test
    void consecutiveFailuresTripAndSkipInstance() {
        CircuitBreakerSettings settings = settings(CircuitBreakerSettings.ALL);
        InstanceCircuitBreaker breaker = new InstanceCircuitBreaker(settings);
        ServiceInstance dead = instance("10.0.0.1", 8080);
        ServiceInstance live = instance("10.0.0.2", 8080);

        for (int i = 0; i < 5; i++) {
            breaker.onResult(dead, false);
        }

        assertEquals(1, breaker.openCount());
        List<ServiceInstance> available = breaker.filterAvailable(List.of(dead, live));
        assertEquals(1, available.size());
        assertEquals("10.0.0.2", available.get(0).getHost());
    }

    @Test
    void successResetsConsecutiveCount() {
        CircuitBreakerSettings settings = settings(CircuitBreakerSettings.ALL);
        InstanceCircuitBreaker breaker = new InstanceCircuitBreaker(settings);
        ServiceInstance inst = instance("10.0.0.1", 8080);

        for (int i = 0; i < 4; i++) {
            breaker.onResult(inst, false);
        }
        breaker.onResult(inst, true);
        breaker.onResult(inst, false);

        assertEquals(0, breaker.openCount());
        assertTrue(breaker.tryEnter(inst));
    }

    @Test
    void allRecoveryLetsEveryoneInAfterOpenSeconds() {
        AtomicLong now = new AtomicLong(1_000);
        CircuitBreakerSettings settings = settings(CircuitBreakerSettings.ALL);
        InstanceCircuitBreaker breaker = new InstanceCircuitBreaker(settings, now::get);
        ServiceInstance inst = instance("10.0.0.1", 8080);

        for (int i = 0; i < 5; i++) {
            breaker.onResult(inst, false);
        }
        assertFalse(breaker.tryEnter(inst));

        now.addAndGet(10_000);
        assertTrue(breaker.tryEnter(inst));
        assertTrue(breaker.tryEnter(inst));
        assertEquals(0, breaker.openCount());
    }

    @Test
    void halfRecoveryAllowsOnlyOneProbe() {
        AtomicLong now = new AtomicLong(1_000);
        CircuitBreakerSettings settings = settings(CircuitBreakerSettings.HALF);
        InstanceCircuitBreaker breaker = new InstanceCircuitBreaker(settings, now::get);
        ServiceInstance inst = instance("10.0.0.1", 8080);

        for (int i = 0; i < 5; i++) {
            breaker.onResult(inst, false);
        }
        now.addAndGet(10_000);

        assertTrue(breaker.tryEnter(inst));
        assertFalse(breaker.tryEnter(inst));
        assertEquals(1, breaker.openCount());
    }

    @Test
    void halfProbeSuccessCloses() {
        AtomicLong now = new AtomicLong(1_000);
        CircuitBreakerSettings settings = settings(CircuitBreakerSettings.HALF);
        InstanceCircuitBreaker breaker = new InstanceCircuitBreaker(settings, now::get);
        ServiceInstance inst = instance("10.0.0.1", 8080);

        for (int i = 0; i < 5; i++) {
            breaker.onResult(inst, false);
        }
        now.addAndGet(10_000);
        assertTrue(breaker.tryEnter(inst));
        breaker.onResult(inst, true);

        assertEquals(0, breaker.openCount());
        assertTrue(breaker.tryEnter(inst));
    }

    @Test
    void halfProbeFailureReopens() {
        AtomicLong now = new AtomicLong(1_000);
        CircuitBreakerSettings settings = settings(CircuitBreakerSettings.HALF);
        InstanceCircuitBreaker breaker = new InstanceCircuitBreaker(settings, now::get);
        ServiceInstance inst = instance("10.0.0.1", 8080);

        for (int i = 0; i < 5; i++) {
            breaker.onResult(inst, false);
        }
        now.addAndGet(10_000);
        assertTrue(breaker.tryEnter(inst));
        breaker.onResult(inst, false);

        assertFalse(breaker.tryEnter(inst));
        now.addAndGet(9_000);
        assertFalse(breaker.tryEnter(inst));
        now.addAndGet(1_000);
        assertTrue(breaker.tryEnter(inst));
    }

    @Test
    void recoveryBlankDefaultsToAll() {
        CircuitBreakerSettings settings = new CircuitBreakerSettings();
        settings.setRecovery("HALF");
        assertEquals(CircuitBreakerSettings.HALF, settings.getRecovery());
        settings.setRecovery("nope");
        assertEquals(CircuitBreakerSettings.ALL, settings.getRecovery());
    }

    @Test
    void thresholdOneTripsOnFirstFailure() {
        CircuitBreakerSettings settings = settings(CircuitBreakerSettings.ALL);
        settings.setFailureThreshold(1);
        InstanceCircuitBreaker breaker = new InstanceCircuitBreaker(settings);
        ServiceInstance inst = instance("10.0.0.1", 8080);

        breaker.onResult(inst, false);

        assertEquals(1, breaker.openCount());
        assertFalse(breaker.tryEnter(inst));
    }

    @Test
    void expireAtExactOpenUntilAllows() {
        AtomicLong now = new AtomicLong(1_000);
        CircuitBreakerSettings settings = settings(CircuitBreakerSettings.ALL);
        settings.setOpenSeconds(10);
        InstanceCircuitBreaker breaker = new InstanceCircuitBreaker(settings, now::get);
        ServiceInstance inst = instance("10.0.0.1", 8080);
        trip(breaker, inst);

        now.set(10_999);
        assertFalse(breaker.tryEnter(inst));
        now.set(11_000);
        assertTrue(breaker.tryEnter(inst));
    }

    @Test
    void sameHostDifferentPortsAreIsolated() {
        InstanceCircuitBreaker breaker = new InstanceCircuitBreaker(settings(CircuitBreakerSettings.ALL));
        ServiceInstance a = instance("10.0.0.1", 8080);
        ServiceInstance b = instance("10.0.0.1", 8081);
        trip(breaker, a);

        assertFalse(breaker.tryEnter(a));
        assertTrue(breaker.tryEnter(b));
    }

    @Test
    void allOpenReturnsEmptyListNotNull() {
        InstanceCircuitBreaker breaker = new InstanceCircuitBreaker(settings(CircuitBreakerSettings.ALL));
        ServiceInstance a = instance("10.0.0.1", 8080);
        ServiceInstance b = instance("10.0.0.2", 8080);
        trip(breaker, a);
        trip(breaker, b);

        List<ServiceInstance> available = breaker.filterAvailable(List.of(a, b));
        assertTrue(available.isEmpty());
        assertEquals(2, breaker.openCount());
    }

    @Test
    void nullAndEmptyInputsDoNotThrow() {
        InstanceCircuitBreaker breaker = new InstanceCircuitBreaker(null, null);
        assertTrue(breaker.filterAvailable(null) == null);
        assertTrue(breaker.filterAvailable(List.of()).isEmpty());
        assertFalse(breaker.tryEnter(null));
        breaker.onResult(null, false);
        breaker.onResult(null, true);
        assertEquals(0, breaker.openCount());
    }

    @Test
    void successOnUnknownInstanceIsNoop() {
        InstanceCircuitBreaker breaker = new InstanceCircuitBreaker(settings(CircuitBreakerSettings.ALL));
        breaker.onResult(instance("10.0.0.1", 8080), true);
        assertEquals(0, breaker.openCount());
    }

    @Test
    void inflightSuccessClosesDuringRest() {
        AtomicLong now = new AtomicLong(1_000);
        InstanceCircuitBreaker breaker = new InstanceCircuitBreaker(settings(CircuitBreakerSettings.ALL), now::get);
        ServiceInstance inst = instance("10.0.0.1", 8080);
        trip(breaker, inst);

        breaker.onResult(inst, true);

        assertEquals(0, breaker.openCount());
        assertTrue(breaker.tryEnter(inst));
    }

    @Test
    void inflightFailureDuringOpenExtendsRest() {
        AtomicLong now = new AtomicLong(1_000);
        InstanceCircuitBreaker breaker = new InstanceCircuitBreaker(settings(CircuitBreakerSettings.ALL), now::get);
        ServiceInstance inst = instance("10.0.0.1", 8080);
        trip(breaker, inst);

        now.set(6_000);
        breaker.onResult(inst, false);
        now.set(11_000);
        assertFalse(breaker.tryEnter(inst));
        now.set(16_000);
        assertTrue(breaker.tryEnter(inst));
    }

    @Test
    void doubleCloseDoesNotGoNegative() {
        InstanceCircuitBreaker breaker = new InstanceCircuitBreaker(settings(CircuitBreakerSettings.ALL));
        ServiceInstance inst = instance("10.0.0.1", 8080);
        trip(breaker, inst);
        breaker.onResult(inst, true);
        breaker.onResult(inst, true);
        assertEquals(0, breaker.openCount());
    }

    @Test
    void halfOnlyOneProbeUnderContention() throws Exception {
        AtomicLong now = new AtomicLong(1_000);
        InstanceCircuitBreaker breaker = new InstanceCircuitBreaker(settings(CircuitBreakerSettings.HALF), now::get);
        ServiceInstance inst = instance("10.0.0.1", 8080);
        trip(breaker, inst);
        now.addAndGet(10_000);

        int threads = 16;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger entered = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                go.await();
                if (breaker.tryEnter(inst)) {
                    entered.incrementAndGet();
                }
                return null;
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, entered.get());
        assertEquals(1, breaker.openCount());
    }

    @Test
    void halfInflightFailureCancelsProbeConservatively() {
        AtomicLong now = new AtomicLong(1_000);
        InstanceCircuitBreaker breaker = new InstanceCircuitBreaker(settings(CircuitBreakerSettings.HALF), now::get);
        ServiceInstance inst = instance("10.0.0.1", 8080);
        trip(breaker, inst);
        now.addAndGet(10_000);
        assertTrue(breaker.tryEnter(inst));

        breaker.onResult(inst, false);

        assertFalse(breaker.tryEnter(inst));
        assertEquals(1, breaker.openCount());
    }

    private static void trip(InstanceCircuitBreaker breaker, ServiceInstance inst) {
        for (int i = 0; i < 5; i++) {
            breaker.onResult(inst, false);
        }
    }

    private static CircuitBreakerSettings settings(String recovery) {
        CircuitBreakerSettings settings = new CircuitBreakerSettings();
        settings.setEnabled(true);
        settings.setFailureThreshold(5);
        settings.setOpenSeconds(10);
        settings.setRecovery(recovery);
        return settings;
    }

    private static ServiceInstance instance(String host, int port) {
        ServiceInstance instance = new ServiceInstance();
        instance.setHost(host);
        instance.setPort(port);
        return instance;
    }
}
