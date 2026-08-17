package com.rover.nameserver.client.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rover.common.model.ServiceInstance;
import java.util.List;
import org.junit.jupiter.api.Test;

class InstanceCacheTest {

    @Test
    void oldQueryCannotOverwriteNewerPushInSameEpoch() {
        InstanceCache cache = new InstanceCache();
        cache.applyPush("svc", null, "epoch-1", 2, List.of(instance("new")));

        cache.putSnapshotFromQuery("svc", null, "epoch-1", 1, List.of(instance("old")));

        assertEquals("new", cache.get("svc", null).get(0).getInstanceId());
        assertEquals(2, cache.revision("svc", null));
    }

    private static ServiceInstance instance(String id) {
        ServiceInstance instance = new ServiceInstance();
        instance.setInstanceId(id);
        instance.setHost("127.0.0.1");
        instance.setPort(8080);
        return instance;
    }
}
