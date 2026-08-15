package com.rover.nameserver.core.push;

import io.netty.channel.Channel;
import java.util.Collections;
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
}