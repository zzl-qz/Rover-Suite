package com.rover.nameserver.core.push;

import com.rover.common.codec.RoverMessageCodecSupport;
import com.rover.common.model.ServiceInstance;
import com.rover.common.protocol.PushType;
import com.rover.common.protocol.ServicePushBody;
import com.rover.nameserver.core.cluster.NameserverGeneration;
import com.rover.nameserver.core.metrics.NameserverMetricsRegistry;
import com.rover.nameserver.core.registry.RegistrySnapshot;
import io.netty.channel.Channel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-06 09:00:00
 * Description: 服务变更推送：按订阅组裁剪实例列表后推送，通配订阅仍收整份快照
 */
@Slf4j
public class PushService {

    private final SubscriptionManager subscriptionManager;
    private final AtomicBoolean pushEnabled;
    private final AtomicLong pushIdGenerator = new AtomicLong(1);
    /** 指标注册表：推送次数埋点（仅统计真实发生的推送）。 */
    private final NameserverMetricsRegistry metrics;
    @Getter
    private final NameserverGeneration generation;

    public PushService(
            SubscriptionManager subscriptionManager,
            boolean pushEnabled,
            NameserverGeneration generation) {
        this(subscriptionManager, pushEnabled, generation, null);
    }

    public PushService(
            SubscriptionManager subscriptionManager,
            boolean pushEnabled,
            NameserverGeneration generation,
            NameserverMetricsRegistry metrics) {
        this.subscriptionManager = subscriptionManager;
        this.pushEnabled = new AtomicBoolean(pushEnabled);
        this.generation = Objects.requireNonNull(generation, "generation");
        this.metrics = metrics == null ? new NameserverMetricsRegistry() : metrics;
    }

    public boolean isPushEnabled() {
        return pushEnabled.get();
    }

    public void setPushEnabled(boolean enabled) {
        this.pushEnabled.set(enabled);
    }

    public String getEpoch() {
        return generation.epoch();
    }


    /**
     * 按订阅组分别推送。
     * 通配订阅收到整份名单；订了具体组的连接只收到这一组，避免和查询结果不一致。
     */
    public void pushSnapshot(RegistrySnapshot snapshot) {
        if (!pushEnabled.get() || snapshot == null) {
            return;
        }
        List<String> groups = subscriptionManager.groupsToNotify(
                snapshot.getServiceName(), snapshot.getGroup());
        if (groups.isEmpty()) {
            return;
        }
        long pushId = pushIdGenerator.getAndIncrement();
        int delivered = 0;
        String epoch = null;
        for (String group : groups) {
            ServicePushBody body = toPushBody(
                    snapshot, wireGroup(group), instancesForGroup(snapshot.getInstances(), group));
            epoch = body.getEpoch();
            for (Channel channel : subscriptionManager.channels(snapshot.getServiceName(), group)) {
                if (channel == null) {
                    continue;
                }
                if (!channel.isActive()) {
                    subscriptionManager.removeChannel(channel);
                    continue;
                }
                channel.writeAndFlush(RoverMessageCodecSupport.push(pushId, body)).addListener(future -> {
                    if (!future.isSuccess()) {
                        log.warn("推送失败: service={}, channel={}",
                                snapshot.getServiceName(), channel.remoteAddress(), future.cause());
                    }
                });
                delivered++;
            }
        }
        if (delivered == 0) {
            return;
        }
        metrics.push(snapshot.getServiceName(), delivered);
        log.info("推送服务变更: service={}, revision={}, epoch={}, term={}, subscribers={}",
                snapshot.getServiceName(),
                snapshot.getRevision(),
                epoch,
                generation.term(),
                delivered);
    }

    /** 初次订阅只向发起订阅的连接发送当前快照，避免把老订阅者全部广播一遍。 */
    public void pushSnapshotTo(RegistrySnapshot snapshot, Channel channel) {
        if (!pushEnabled.get() || snapshot == null || channel == null || !channel.isActive()) {
            return;
        }
        metrics.push(snapshot.getServiceName(), 1);
        ServicePushBody body = toPushBody(snapshot, snapshot.getGroup(), snapshot.getInstances());
        long pushId = pushIdGenerator.getAndIncrement();
        channel.writeAndFlush(RoverMessageCodecSupport.push(pushId, body)).addListener(future -> {
            if (!future.isSuccess()) {
                log.warn("初始快照推送失败: service={}, channel={}",
                        snapshot.getServiceName(), channel.remoteAddress(), future.cause());
            }
        });
    }

    private ServicePushBody toPushBody(
            RegistrySnapshot snapshot, String group, List<ServiceInstance> instances) {
        ServicePushBody body = new ServicePushBody();
        body.setServiceName(snapshot.getServiceName());
        body.setGroup(group);
        body.setInstances(instances == null ? List.of() : instances);
        body.setRevision(snapshot.getRevision());
        body.setEpoch(generation.epoch());
        body.setPushType(PushType.SNAPSHOT.name());
        return body;
    }

    /** 通配订阅保留整份；具体组只保留 group 相等的实例，规则与注册表 query 一致。 */
    private static List<ServiceInstance> instancesForGroup(List<ServiceInstance> instances, String group) {
        if (instances == null || instances.isEmpty()) {
            return List.of();
        }
        if (group == null || group.isBlank()) {
            return instances;
        }
        List<ServiceInstance> filtered = new ArrayList<>();
        for (ServiceInstance instance : instances) {
            if (instance != null && group.equals(instance.getGroup())) {
                filtered.add(instance);
            }
        }
        return filtered;
    }

    private static String wireGroup(String group) {
        return group == null || group.isBlank() ? null : group;
    }
}
