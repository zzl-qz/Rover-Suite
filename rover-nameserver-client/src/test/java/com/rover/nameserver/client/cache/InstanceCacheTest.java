package com.rover.nameserver.client.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.model.ServiceInstance;
import com.rover.nameserver.client.cache.InstanceCache.ApplyOutcome;
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

    /**
     * 某组最后一台实例下线时，服务端会 bump revision 推空包；这包必须被认下来。
     *
     * 否则本地会一直留着已经下线的实例继续转发——「v2 已经全量回滚了，流量还在打 v2」就是这么来的。
     */
    @Test
    void newerEmptyPushClearsInstancesOfThatGroupOnly() {
        InstanceCache cache = new InstanceCache();
        cache.applyPush("svc", "v1", "epoch-1", 1, List.of(instance("v1-1", "v1")));
        cache.applyPush("svc", "v2", "epoch-1", 1, List.of(instance("v2-1", "v2")));

        assertEquals(ApplyOutcome.APPLIED, cache.applyPush("svc", "v2", "epoch-1", 2, List.of()));

        assertTrue(cache.get("svc", "v2").isEmpty(), "v2 清空后不得再返回它的旧实例");
        assertEquals(1, cache.get("svc", "v1").size(), "推空只描述 v2 这一组，v1 的名单不该被动");
    }

    /** 不比本地新的空包按乱序旧包处理：它描述的只是「推送发出时」的状态，不能用来抹掉更新的名单。 */
    @Test
    void emptyPushThatIsNotNewerIsTreatedAsSpurious() {
        InstanceCache cache = new InstanceCache();
        cache.applyPush("svc", "v1", "epoch-1", 7, List.of(instance("v1-1")));

        assertEquals(ApplyOutcome.REJECTED_EMPTY_PROTECT,
                cache.applyPush("svc", "v1", "epoch-1", 7, List.of()));
        assertEquals(ApplyOutcome.REJECTED_STALE,
                cache.applyPush("svc", "v1", "epoch-1", 6, List.of()));

        assertEquals(1, cache.get("svc", "v1").size(), "空包不是更新的，必须保留原名单");
    }

    /**
     * 具体组没有自己的缓存时如实返回空，绝不退回含其它组实例的整服务缓存。
     *
     * 退回会让 v2 的请求打到 v1 的实例上：灰度比例直接失真，而且现象上极难看出是缓存回落导致的。
     */
    @Test
    void coldGroupCacheNeverFallsBackToWholeServiceCache() {
        InstanceCache cache = new InstanceCache();
        cache.applyPush("svc", null, "epoch-1", 1, List.of(instance("v1-1", "v1")));

        assertTrue(cache.get("svc", "v2").isEmpty(),
                "v2 还没有自己的快照，必须返回空，不能退回整服务缓存");
        assertEquals(1, cache.get("svc", null).size(), "通配组自己的缓存不受影响");
    }

    private static ServiceInstance instance(String id) {
        return instance(id, null);
    }

    private static ServiceInstance instance(String id, String group) {
        ServiceInstance instance = new ServiceInstance();
        instance.setInstanceId(id);
        instance.setHost("127.0.0.1");
        instance.setPort(8080);
        instance.setGroup(group);
        return instance;
    }
}
