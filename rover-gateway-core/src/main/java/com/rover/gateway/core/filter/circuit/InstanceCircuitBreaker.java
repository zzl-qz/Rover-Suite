package com.rover.gateway.core.filter.circuit;

import com.rover.common.model.ServiceInstance;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * 按 host:port 连续失败熔断。全健康时只读 openCount，不分配、不加锁。
 */
public final class InstanceCircuitBreaker {

    private static final int MAX_KEYS = 4096;

    private static final int CLOSED = 0;
    private static final int OPEN = 1;
    private static final int HALF_OPEN = 2;

    private final CircuitBreakerSettings settings;
    private final LongSupplier clock;
    private final Map<String, Node> nodes = new ConcurrentHashMap<>();
    private final AtomicInteger openCount = new AtomicInteger();

    public InstanceCircuitBreaker(CircuitBreakerSettings settings) {
        this(settings, System::currentTimeMillis);
    }

    InstanceCircuitBreaker(CircuitBreakerSettings settings, LongSupplier clock) {
        this.settings = settings == null ? new CircuitBreakerSettings() : settings;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /**
     * 没有打开的实例就原样返回同一份列表。
     * 有打开的才 new 一份，跳过休息中的；到期按 recovery 放行或只抢 1 个探测。
     */
    public List<ServiceInstance> filterAvailable(List<ServiceInstance> instances) {
        if (instances == null || instances.isEmpty() || openCount.get() == 0) {
            return instances;
        }
        List<ServiceInstance> available = new ArrayList<>(instances.size());
        for (ServiceInstance instance : instances) {
            if (tryEnter(instance)) {
                available.add(instance);
            }
        }
        return available;
    }

    public boolean tryEnter(ServiceInstance instance) {
        if (instance == null) {
            return false;
        }
        Node node = nodes.get(instanceKey(instance));
        if (node == null) {
            return true;
        }
        return node.tryEnter(settings, clock.getAsLong(), openCount);
    }

    public void onResult(ServiceInstance instance, boolean success) {
        if (instance == null) {
            return;
        }
        String key = instanceKey(instance);
        if (success) {
            Node node = nodes.get(key);
            if (node != null) {
                node.onSuccess(openCount);
            }
            return;
        }
        if (nodes.size() >= MAX_KEYS && !nodes.containsKey(key)) {
            return;
        }
        nodes.computeIfAbsent(key, ignored -> new Node())
                .onFailure(settings, clock.getAsLong(), openCount);
    }

    int openCount() {
        return openCount.get();
    }

    static String instanceKey(ServiceInstance instance) {
        return instance.getHost() + ":" + instance.getPort();
    }

    private static final class Node {

        private final AtomicInteger state = new AtomicInteger(CLOSED);
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicInteger probeInFlight = new AtomicInteger();
        private volatile long openUntilMillis;

        private boolean tryEnter(CircuitBreakerSettings settings, long now, AtomicInteger openCount) {
            int current = state.get();
            if (current == CLOSED) {
                return true;
            }
            if (current == HALF_OPEN) {
                return false;
            }
            if (now < openUntilMillis) {
                return false;
            }
            if (settings.isAllRecovery()) {
                close(openCount);
                return true;
            }
            if (probeInFlight.compareAndSet(0, 1)) {
                if (state.compareAndSet(OPEN, HALF_OPEN)) {
                    return true;
                }
                probeInFlight.set(0);
            }
            return false;
        }

        private void onSuccess(AtomicInteger openCount) {
            failures.set(0);
            close(openCount);
        }

        private void onFailure(CircuitBreakerSettings settings, long now, AtomicInteger openCount) {
            int current = state.get();
            if (current == HALF_OPEN) {
                if (state.compareAndSet(HALF_OPEN, OPEN)) {
                    openUntilMillis = now + openMillis(settings);
                    probeInFlight.set(0);
                }
                return;
            }
            if (current == OPEN) {
                openUntilMillis = now + openMillis(settings);
                return;
            }
            if (failures.incrementAndGet() >= settings.getFailureThreshold()
                    && state.compareAndSet(CLOSED, OPEN)) {
                openUntilMillis = now + openMillis(settings);
                probeInFlight.set(0);
                openCount.incrementAndGet();
            }
        }

        private void close(AtomicInteger openCount) {
            int previous = state.getAndSet(CLOSED);
            failures.set(0);
            probeInFlight.set(0);
            if (previous == OPEN || previous == HALF_OPEN) {
                openCount.updateAndGet(value -> Math.max(0, value - 1));
            }
        }

        private static long openMillis(CircuitBreakerSettings settings) {
            return Math.max(1, settings.getOpenSeconds()) * 1000L;
        }
    }
}
