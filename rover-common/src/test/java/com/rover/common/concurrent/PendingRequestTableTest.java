package com.rover.common.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PendingRequestTableTest {

    @Test
    void enforcesCapacityAndReleasesPermitOnCompletion() {
        PendingRequestTable<String> table = new PendingRequestTable<>(1);
        table.create(1, 1000);
        assertThrows(IllegalStateException.class, () -> table.create(2, 1000));
        table.complete(1, "ok");
        table.create(2, 1000);
        assertEquals(1, table.size());
        table.close();
    }
}
