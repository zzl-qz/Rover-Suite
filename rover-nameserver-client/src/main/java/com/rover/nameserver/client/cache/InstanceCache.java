package com.rover.nameserver.client.cache;

import com.rover.common.model.ServiceInstance;
import com.rover.common.protocol.ServicePushBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:55:00
 * Description: 客户端本地实例缓存
 */
public class InstanceCache {

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final List<Consumer<ServicePushBody>> listeners = new CopyOnWriteArrayList<>();

    public void putSnapshot(String serviceName, String group, long revision, List<ServiceInstance> instances) {
        String key = cacheKey(serviceName, group);
        cache.put(key, new CacheEntry(revision, copy(instances)));
    }

    public void onPush(ServicePushBody pushBody) {
        if (pushBody == null || pushBody.getServiceName() == null) {
            return;
        }
        putSnapshot(
                pushBody.getServiceName(),
                pushBody.getGroup(),
                pushBody.getRevision(),
                pushBody.getInstances());
        for (Consumer<ServicePushBody> listener : listeners) {
            listener.accept(pushBody);
        }
    }

    public List<ServiceInstance> get(String serviceName, String group) {
        CacheEntry entry = cache.get(cacheKey(serviceName, group));
        if (entry == null && group != null && !group.isBlank()) {
            // 没按 group 缓存时，退回看全量
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

    public void addListener(Consumer<ServicePushBody> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void clear() {
        cache.clear();
    }

    private String cacheKey(String serviceName, String group) {
        return serviceName + "#" + (group == null ? "" : group);
    }

    private List<ServiceInstance> copy(List<ServiceInstance> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }

    private static final class CacheEntry {
        private final long revision;
        private final List<ServiceInstance> instances;

        private CacheEntry(long revision, List<ServiceInstance> instances) {
            this.revision = revision;
            this.instances = instances;
        }
    }
}
