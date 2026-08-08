package com.rover.nameserver.core.push;

import io.netty.channel.Channel;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: 订阅关系管理
 */
public class SubscriptionManager {

    // serviceName -> group -> channels
    private final Map<String, Map<String, Set<Channel>>> subscriptions = new ConcurrentHashMap<>();

    public void subscribe(String serviceName, String group, Channel channel) {
        String normalizedGroup = normalizeGroup(group);
        subscriptions
                .computeIfAbsent(serviceName, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(normalizedGroup, key -> ConcurrentHashMap.newKeySet())
                .add(channel);
    }

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
        if (channels.isEmpty()) {
            byGroup.remove(normalizedGroup, channels);
        }
        if (byGroup.isEmpty()) {
            subscriptions.remove(serviceName, byGroup);
        }
    }

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

    public Set<Channel> findSubscribers(String serviceName, String instanceGroup) {
        Map<String, Set<Channel>> byGroup = subscriptions.get(serviceName);
        if (byGroup == null || byGroup.isEmpty()) {
            return Set.of();
        }

        Set<Channel> result = ConcurrentHashMap.newKeySet();
        // group 为空的订阅：吃这个服务所有变更
        Set<Channel> allGroupSubscribers = byGroup.get("");
        if (allGroupSubscribers != null) {
            result.addAll(allGroupSubscribers);
        }
        // 指定 group 的订阅
        if (instanceGroup != null && !instanceGroup.isBlank()) {
            Set<Channel> groupSubscribers = byGroup.get(instanceGroup);
            if (groupSubscribers != null) {
                result.addAll(groupSubscribers);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private String normalizeGroup(String group) {
        return group == null || group.isBlank() ? "" : group;
    }
}
