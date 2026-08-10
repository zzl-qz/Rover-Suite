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
 *
 * 核心职责：在客户端本地以服务维度缓存 Nameserver 返回的实例快照，
 * 供查询 API 与订阅推送共用一份数据，并对外提供变更监听回调。
 * 由 NameserverClient 持有使用：query 成功后写入快照，服务端推送到达时
 * 由 onPush 更新缓存并触发监听器。
 *
 * 线程安全：cache 用 ConcurrentHashMap，listeners 用 CopyOnWriteArrayList，可被
 * Netty IO 线程与业务线程并发访问。实例列表在写入/读取时均做防御性拷贝，避免外部修改。
 */
public class InstanceCache {

    /** serviceName#group -> 缓存项 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    /** 推送回调 */
    private final List<Consumer<ServicePushBody>> listeners = new CopyOnWriteArrayList<>();

    /**
     * 写入一份实例快照。
     *
     * @param serviceName 服务名
     * @param group       分组名，可为 null（表示不分组）
     * @param revision    服务端版本号，用于增量判断
     * @param instances   实例列表快照
     */
    public void putSnapshot(String serviceName, String group, long revision, List<ServiceInstance> instances) {
        String key = cacheKey(serviceName, group);
        // 整项覆盖替换，实例列表拷贝一份防止外部改动污染缓存
        cache.put(key, new CacheEntry(revision, copy(instances)));
    }

    /**
     * 处理服务端主动推送：先按推送内容刷新缓存，再逐个通知监听器。
     *
     * @param pushBody 服务端推送的服务变更体（可能携带最新实例快照）
     */
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

    /**
     * 读取某服务的实例列表，返回的是防御性拷贝，外部可安全持有。
     *
     * @param serviceName 服务名
     * @param group       分组名；指定分组查不到时自动退回查全量（group=null）缓存
     * @return 实例列表；无缓存时返回空列表，不返回 null
     */
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

    /**
     * 读取某服务当前缓存的版本号，供订阅时携带 knownRevision 做增量判断。
     *
     * @return 缓存版本号；无缓存时返回 0
     */
    public long revision(String serviceName, String group) {
        CacheEntry entry = cache.get(cacheKey(serviceName, group));
        return entry == null ? 0L : entry.revision;
    }

    /**
     * 注册推送监听器，服务端推送到达时会回调。
     *
     * @param listener 监听回调；为 null 时忽略
     */
    public void addListener(Consumer<ServicePushBody> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** 清空全部缓存，客户端关闭时调用。 */
    public void clear() {
        cache.clear();
    }

    /**
     * 拼接缓存键：serviceName + "#" + group，group 为空串与 null 视为同一键（全量）。
     */
    private String cacheKey(String serviceName, String group) {
        return serviceName + "#" + (group == null ? "" : group);
    }

    /**
     * 防御性拷贝：返回一个新 ArrayList，避免缓存内的列表被外部引用直接修改。
     */
    private List<ServiceInstance> copy(List<ServiceInstance> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }

    /** 单个服务的缓存项：不可变，写入时整体替换。 */
    private static final class CacheEntry {
        /** 版本号 */
        private final long revision;
        /** 实例列表 */
        private final List<ServiceInstance> instances;

        private CacheEntry(long revision, List<ServiceInstance> instances) {
            this.revision = revision;
            this.instances = instances;
        }
    }
}
