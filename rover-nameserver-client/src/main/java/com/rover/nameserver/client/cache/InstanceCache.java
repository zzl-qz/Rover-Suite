package com.rover.nameserver.client.cache;

import com.rover.common.model.ServiceInstance;
import com.rover.common.util.ServiceKeys;
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
        List<ServiceInstance> incoming = instances == null ? List.of() : instances;
        ApplyOutcome[] outcome = {ApplyOutcome.APPLIED};
        CacheEntry[] notifyEntry = new CacheEntry[1];
        cache.compute(key, (ignored, current) -> {
            // 推空保护：疑似误推空，先保住本地，并累计拒绝次数触发强制对账
            if (incoming.isEmpty() && current != null && !current.instances.isEmpty()) {
                outcome[0] = ApplyOutcome.REJECTED_EMPTY_PROTECT;
                if (incrementReject(current)) {
                    notifyEntry[0] = current;
                }
                return current;
            }

            // 同世代且更旧 → 拒（乱序旧包）；epoch 为空视为老协议，不做拒旧
            if (isOlderSameEpoch(current, epoch, revision)) {
                outcome[0] = ApplyOutcome.REJECTED_STALE;
                if (incrementReject(current)) {
                    notifyEntry[0] = current;
                }
                return current;
            }

            return new CacheEntry(
                    serviceName, group, epoch, revision, copy(incoming), new AtomicInteger());
        });
        if (notifyEntry[0] != null) {
            notifyForceQuery(notifyEntry[0]);
        }
        return outcome[0];
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
        cache.compute(key, (ignored, current) -> {
            // query 可能比并发 push 更晚返回；同 epoch 下不得用旧快照覆盖新推送。
            if (isOlderSameEpoch(current, epoch, revision)) {
                return current;
            }
            return new CacheEntry(
                    serviceName, group, epoch, revision, copy(instances), new AtomicInteger());
        });
    }

    /**
     * @deprecated 兼容旧调用，等价于对账覆盖。
     * @DL 兼容 API：旧客户端迁移期间可继续使用，新代码请调用带 epoch 的方法。
     */
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

    /**
     * @DL 扩展 API：供 NameserverClient 暴露推送回调；Gateway 内部不直接消费。
     */
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

    // 拒绝次数+1  最多就是拒绝3次
    private boolean incrementReject(CacheEntry current) {
        return current.rejectCount.incrementAndGet() == FORCE_QUERY_AFTER_REJECTS;
    }

    // 唤醒注册的强制对账监听器
    private void notifyForceQuery(CacheEntry current) {
        for (BiConsumer<String, String> listener : forceQueryListeners) {
            listener.accept(current.serviceName, current.group);
        }
    }

    private boolean isOlderSameEpoch(CacheEntry current, String epoch, long revision) {
        return current != null
                && epoch != null
                && !epoch.isBlank()
                && epoch.equals(current.epoch)
                && revision < current.revision;
    }

    private String cacheKey(String serviceName, String group) {
        return ServiceKeys.serviceGroup(serviceName, group);
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
