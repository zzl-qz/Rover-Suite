package com.rover.agent.core.repository;

import com.rover.agent.core.model.AlertDelivery;
import com.rover.agent.core.model.AlertLifecycle;
import java.util.Optional;
import java.util.function.Function;

/** Transactional idempotency receipts and current firing/resolved lifecycle per authenticated source. */
public interface AlertLifecycleRepository {
    <T> T transaction(Function<Transaction, T> work);

    interface Transaction {
        Optional<AlertDelivery> delivery(String sourceId, String deliveryId);
        Optional<AlertLifecycle> lifecycle(String sourceId, String alertKey);
        boolean saveDelivery(AlertDelivery delivery);
        void saveLifecycle(AlertLifecycle lifecycle);
        void pruneDeliveriesBefore(long acceptedBeforeMillis);
    }
}
