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
 *
 * 核心职责：维护「哪些连接订阅了哪些服务」的三级订阅表，
 * 支持订阅/退订/连接失效清理/按服务查询订阅者，是多线程环境下的核心数据结构。</p>
 *
 * 并发结构：{@code ConcurrentHashMap<serviceName, ConcurrentHashMap<group, Set<Channel>>>}
 * 三层嵌套，所有层级均用并发容器，保证注册线程、推送线程、
 * 健康检查线程可同时安全读写，无需全局加锁。</p>
 *
 * 组（group）语义：group 为空字符串表示「通配订阅」——消费该服务下所有组的变更；
 * 指定 group 的订阅只收到该组变更。空组订阅与指定组订阅可同时存在。</p>
 *
 * 被 PushService 查询订阅者、被请求分发器在订阅/退订/断线时更新；
 * 与 {@link PushService} 构成推送链路的两环。</p>
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

    /**
     * 取消单条订阅关系，并级联清理空组、空服务，防止死数据累积。
     *
     * @param serviceName 服务名
     * @param group       组（null/空白对应通配组 "")
     * @param channel     要退订的连接
     */
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

    /**
     * 连接失效时的全量清理：从该连接订阅的所有服务/组中移除，
     * 并级联清理空组与空服务。由断线处理（dispatcher.onChannelInactive）调用。
     *
     * @param channel 已失效的连接
     */
    public void removeChannel(Channel channel) {
        // 遍历所有服务的所有组，用迭代器安全移除该 channel
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
     * 查询某服务下所有应接收变更的连接（含去重）。
     * 匹配规则：通配组订阅者（group=""）一律返回；若实例组非空，再加入该组的指定订阅者。
     *
     * @param serviceName   服务名
     * @param instanceGroup 发生变更的实例所属组（可为 null）
     * @return 去重后的订阅者集合（不可修改）；无人订阅时返回空集合
     */
    public Set<Channel> findSubscribers(String serviceName, String instanceGroup) {
        Map<String, Set<Channel>> byGroup = subscriptions.get(serviceName);
        if (byGroup == null || byGroup.isEmpty()) {
            return Set.of();
        }

        // 内部用并发 set 聚合，保证不重复；最终返回不可修改视图防外部篡改
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

    /** 组名归一化：null 或空白一律归一为 ""（通配组），避免同组多写法 */
    private String normalizeGroup(String group) {
        return group == null || group.isBlank() ? "" : group;
    }
}