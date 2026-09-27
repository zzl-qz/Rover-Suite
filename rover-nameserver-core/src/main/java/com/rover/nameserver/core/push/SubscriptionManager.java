package com.rover.nameserver.core.push;

import io.netty.channel.Channel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Author: Daylight
 * Created: 2026-08-06 11:25:00
 * Description: serviceName → group → Channel 三级订阅表（并发安全），group 为空表示通配订阅
 */
public class SubscriptionManager {

    // serviceName -> group -> channels
    private final Map<String, Map<String, Set<Channel>>> subscriptions = new ConcurrentHashMap<>();

    /**
     * 建立订阅关系：把该连接登记到 (serviceName, group) 的订阅者集合。
     * 三级容器的懒创建与并发安全由 computeIfAbsent 保证。
     *
     * @param serviceName 订阅的服务名
     * @param group       订阅的组；null/空白按通配组 "" 处理
     * @param channel     发起订阅的连接
     */
    public void subscribe(String serviceName, String group, Channel channel) {
        String normalizedGroup = normalizeGroup(group);
        // 原子地创建中间层 map 与最内层 set，避免并发下覆盖已有订阅
        subscriptions
                .computeIfAbsent(serviceName, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(normalizedGroup, key -> ConcurrentHashMap.newKeySet())
                .add(channel);
    }

    /** 取消单条订阅，并级联清理空组、空服务，防止死数据累积。 */
    public void unsubscribe(String serviceName, String group, Channel channel) {
        Map<String, Set<Channel>> byGroup = subscriptions.get(serviceName);
        if (byGroup == null) {
            return;
        }
        String normalizedGroup = normalizeGroup(group);
        Set<Channel> channels = byGroup.get(normalizedGroup);
        if (channels == null) {
            return;
        }
        channels.remove(channel);
        // 组内无订阅者则移除该组；服务下所有组都被移除则移除该服务（条件删除防误删并发新数据）
        if (channels.isEmpty()) {
            byGroup.remove(normalizedGroup, channels);
        }
        if (byGroup.isEmpty()) {
            subscriptions.remove(serviceName, byGroup);
        }
    }

    /** 连接失效时的全量清理：移除该连接的所有订阅，并级联清理空组与空服务。 */
    public void removeChannel(Channel channel) {
        for (Map.Entry<String, Map<String, Set<Channel>>> serviceEntry : subscriptions.entrySet()) {
            Map<String, Set<Channel>> byGroup = serviceEntry.getValue();
            for (Map.Entry<String, Set<Channel>> groupEntry : byGroup.entrySet()) {
                groupEntry.getValue().remove(channel);
            }
            byGroup.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            if (byGroup.isEmpty()) {
                subscriptions.remove(serviceEntry.getKey(), byGroup);
            }
        }
    }

    /**
     * 这次变更要通知哪些订阅组：该服务下**所有**还有订阅者的组。
     *
     * 为什么不只通知「通配 + 变更实例所在的组」：实例可以在组之间迁移（同 instanceId 重新注册到
     * 另一个 group），此时**旧组的名单也变了**，而快照上只带得动新组。只通知新组会让旧组订阅者
     * 一直留着已经迁走的实例，继续把流量打到不该打的机器上。
     *
     * 每个组都会拿到按自己过滤后的名单，因此多通知的组最多收到一次「本组名单没变」的推送，
     * 代价可控，而漏通知会让缓存与注册中心长期不一致。
     *
     * @param serviceName 发生变更的服务名
     * @return 需要通知的订阅组（含通配组 ""）；该服务无人订阅时为空列表
     */
    public List<String> groupsToNotify(String serviceName) {
        Map<String, Set<Channel>> byGroup = subscriptions.get(serviceName);
        if (byGroup == null || byGroup.isEmpty()) {
            return List.of();
        }
        List<String> groups = new ArrayList<>(byGroup.size());
        for (Map.Entry<String, Set<Channel>> entry : byGroup.entrySet()) {
            if (hasChannels(entry.getValue())) {
                groups.add(entry.getKey());
            }
        }
        return groups;
    }

    /** 某个服务、某个订阅组上的连接副本。组名 null 或空白按通配组处理。 */
    public Set<Channel> channels(String serviceName, String group) {
        Map<String, Set<Channel>> byGroup = subscriptions.get(serviceName);
        if (byGroup == null) {
            return Set.of();
        }
        Set<Channel> channels = byGroup.get(normalizeGroup(group));
        if (!hasChannels(channels)) {
            return Set.of();
        }
        return Set.copyOf(channels);
    }

    /** 查询某服务下应接收变更的连接（去重）：通配组订阅者一律返回，实例组非空时再加入指定组订阅者。 */
    public Set<Channel> findSubscribers(String serviceName, String instanceGroup) {
        Map<String, Set<Channel>> byGroup = subscriptions.get(serviceName);
        if (byGroup == null || byGroup.isEmpty()) {
            return Set.of();
        }

        // 内部用并发 set 聚合去重，最终返回不可修改视图防外部篡改
        Set<Channel> result = ConcurrentHashMap.newKeySet();
        // group 为空的订阅：收该服务所有变更
        Set<Channel> allGroupSubscribers = byGroup.get("");
        if (allGroupSubscribers != null) {
            result.addAll(allGroupSubscribers);
        }
        if (instanceGroup != null && !instanceGroup.isBlank()) {
            Set<Channel> groupSubscribers = byGroup.get(instanceGroup);
            if (groupSubscribers != null) {
                result.addAll(groupSubscribers);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /** 组名归一化：null 或空白一律归一为 ""（通配组），避免同组多写法 */
    private String normalizeGroup(String group) {
        return group == null || group.isBlank() ? "" : group;
    }

    private static boolean hasChannels(Set<Channel> channels) {
        return channels != null && !channels.isEmpty();
    }
}