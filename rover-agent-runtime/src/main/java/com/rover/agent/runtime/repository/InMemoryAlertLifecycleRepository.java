package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.AlertDelivery;
import com.rover.agent.core.model.AlertLifecycle;
import com.rover.agent.core.repository.AlertLifecycleRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Atomic in-process implementation for tests and explicit memory mode. */
public final class InMemoryAlertLifecycleRepository implements AlertLifecycleRepository {
    private final Map<String, AlertDelivery> deliveries = new HashMap<>();
    private final Map<String, AlertLifecycle> lifecycles = new HashMap<>();

    @Override
    public synchronized <T> T transaction(Function<Transaction, T> work) {
        Map<String, AlertDelivery> oldDeliveries = new HashMap<>(deliveries);
        Map<String, AlertLifecycle> oldLifecycles = new HashMap<>(lifecycles);
        Transaction tx = new Transaction() {
            @Override public Optional<AlertDelivery> delivery(String sourceId, String deliveryId) {
                return Optional.ofNullable(deliveries.get(key(sourceId, deliveryId)));
            }
            @Override public Optional<AlertLifecycle> lifecycle(String sourceId, String alertKey) {
                return Optional.ofNullable(lifecycles.get(key(sourceId, alertKey)));
            }
            @Override public boolean saveDelivery(AlertDelivery delivery) {
                String key = key(delivery.sourceId(), delivery.deliveryId());
                return deliveries.putIfAbsent(key, delivery) == null;
            }
            @Override public void saveLifecycle(AlertLifecycle lifecycle) {
                lifecycles.put(key(lifecycle.sourceId(), lifecycle.alertKey()), lifecycle);
            }
            @Override public void pruneDeliveriesBefore(long cutoff) {
                deliveries.values().removeIf(item -> item.acceptedAtMillis() < cutoff);
            }
        };
        try { return work.apply(tx); }
        catch (RuntimeException | Error failure) {
            deliveries.clear(); deliveries.putAll(oldDeliveries);
            lifecycles.clear(); lifecycles.putAll(oldLifecycles);
            throw failure;
        }
    }

    private static String key(String sourceId, String value) { return sourceId + '\u0000' + value; }
}
