package com.rover.nameserver.client.cache;

import com.rover.common.model.ServiceInstance;
import com.rover.common.protocol.ServicePushBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Author: Daylight
 * Created: 2026-08-05 10:12:00
 * Description: 客户端本地实例缓存：推送按 epoch+revision 拒旧并带推空保护，查询对账以服务端快照为准全量覆盖
 */
public class InstanceCache {

    /** 连续拒绝多少次推送后，要求 Gateway 强制 query 全量 */
    public static final int FORCE_QUERY_AFTER_REJECTS = 3;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final List<Consumer<ServicePushBody>> listeners = new CopyOnWriteArrayList<>();
    /** 连续拒绝达阈值时回调（serviceName, group），供 Gateway 立刻强制 query */
    private final List<BiConsumer<String, String>> forceQueryListeners = new CopyOnWriteArrayList<>();

    /** 推送应用结果 */
    public enum ApplyOutcome {
        /** 已写入缓存 */
        APPLIED,
        /** 同世代更小 revision，丢弃 */
        REJECTED_STALE,
        /** 推空保护：远端空、本地非空 */
        REJECTED_EMPTY_PROTECT
    }

    /**
     * 处理服务端主动推送。
     */
    public ApplyOutcome onPush(ServicePushBody pushBody) {
        if (pushBody == null || pushBody.getServiceName() == null) {
            return ApplyOutcome.REJECTED_STALE;
        }
        ApplyOutcome outcome = applyPush(
                pushBody.getServiceName(),
                pushBody.getGroup(),
                pushBody.getEpoch(),
                pushBody.getRevision(),
                pushBody.getInstances());
        if (outcome == ApplyOutcome.APPLIED) {
            for (Consumer<ServicePushBody> listener : listeners) {
                listener.accept(pushBody);
            }
        }
        return outcome;
    }

    /**
     * 推送路径写入规则。
     */
    public ApplyOutcome applyPush(
            String serviceName,
            String group,
            String epoch,
            long revision,
            List<ServiceInstance> instances) {
        String key = cacheKey(serviceName, group);
        CacheEntry current = cache.get(key);
        List<ServiceInstance> incoming = instances == null ? List.of() : instances;

        // 推空保护：疑似误推空，先保住本地，并累计拒绝次数触发强制对账
        if (incoming.isEmpty() && current != null && !current.instances.isEmpty()) {
            bumpReject(current);
            return ApplyOutcome.REJECTED_EMPTY_PROTECT;
        }

        // 同世代且更旧 → 拒（乱序旧包）；epoch 为空视为老协议，不做拒旧
        if (current != null
                && epoch != null
                && !epoch.isBlank()
                && epoch.equals(current.epoch)
                && revision < current.revision) {
            bumpReject(current);
            return ApplyOutcome.REJECTED_STALE;
        }

        cache.put(key, new CacheEntry(serviceName, group, epoch, revision, copy(incoming), new AtomicInteger()));
        return ApplyOutcome.APPLIED;
    }

    /**
     * 查询/对账路径：以服务端为准全量覆盖（含空列表），并清掉强制对账标记。
     */
    public void putSnapshotFromQuery(
            String serviceName,
            String group,
            String epoch,
            long revision,
            List<ServiceInstance> instances) {
        String key = cacheKey(serviceName, group);
        cache.put(key, new CacheEntry(serviceName, group, epoch, revision, copy(instances), new AtomicInteger()));
    }

    /** @deprecated 兼容旧调用，等价于对账覆盖 */
    @Deprecated
    public void putSnapshot(String serviceName, String group, long revision, List<ServiceInstance> instances) {
        putSnapshotFromQuery(serviceName, group, null, revision, instances);
    }

    public List<ServiceInstance> get(String serviceName, String group) {
        CacheEntry entry = cache.get(cacheKey(serviceName, group));
        if (entry == null && group != null && !group.isBlank()) {
            entry = cache.get(cacheKey(serviceName, null));
        }
        if (entry == null) {
            return List.of();
        }
        return copy(entry.instances);
    }

    public long revision(String serviceName, String group) {
        CacheEntry entry = cache.get(cacheKey(serviceName, group));
        return entry == null ? 0L : entry.revision;
    }

    public String epoch(String serviceName, String group) {
        CacheEntry entry = cache.get(cacheKey(serviceName, group));
        return entry == null ? null : entry.epoch;
    }

    /**
     * 是否因连续拒绝推送而需要强制全量 query。
     * 返回 true 时会清零计数，避免重复打爆。
     */
    public boolean consumeForceQuery(String serviceName, String group) {
        CacheEntry entry = cache.get(cacheKey(serviceName, group));
        if (entry == null) {
            return false;
        }
        if (entry.rejectCount.get() < FORCE_QUERY_AFTER_REJECTS) {
            return false;
        }
        entry.rejectCount.set(0);
        return true;
    }

    public void addListener(Consumer<ServicePushBody> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** 注册「该强制全量对账了」回调；可能在 Netty 线程触发，监听方自行异步。 */
    public void addForceQueryListener(BiConsumer<String, String> listener) {
        if (listener != null) {
            forceQueryListeners.add(listener);
        }
    }

    public void clear() {
        cache.clear();
    }

    private void bumpReject(CacheEntry current) {
        int n = current.rejectCount.incrementAndGet();
        // 刚达到阈值时通知一次，避免每次拒绝都刷
        if (n == FORCE_QUERY_AFTER_REJECTS) {
            for (BiConsumer<String, String> listener : forceQueryListeners) {
                listener.accept(current.serviceName, current.group);
            }
        }
    }

    private String cacheKey(String serviceName, String group) {
        return serviceName + "#" + (group == null ? "" : group);
    }

    private List<ServiceInstance> copy(List<ServiceInstance> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }

    private static final class CacheEntry {
        private final String serviceName;
        private final String group;
        private final String epoch;
        private final long revision;
        private final List<ServiceInstance> instances;
        private final AtomicInteger rejectCount;

        private CacheEntry(
                String serviceName,
                String group,
                String epoch,
                long revision,
                List<ServiceInstance> instances,
                AtomicInteger rejectCount) {
            this.serviceName = serviceName;
            this.group = group;
            this.epoch = epoch;
            this.revision = revision;
            this.instances = instances;
            this.rejectCount = rejectCount;
        }
    }
}
