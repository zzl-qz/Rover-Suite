package com.rover.nameserver.core.push;

import com.rover.common.codec.RoverMessageCodecSupport;
import com.rover.common.protocol.PushType;
import com.rover.common.protocol.ServicePushBody;
import com.rover.nameserver.core.cluster.NameserverGeneration;
import com.rover.nameserver.core.metrics.NameserverMetricsRegistry;
import com.rover.nameserver.core.registry.RegistrySnapshot;
import io.netty.channel.Channel;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-06 09:00:00
 * Description: 服务变更推送：向订阅连接推送携带 revision 与 generation.epoch 的全量快照，供客户端拒旧/换代判断
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


    /** 推送服务实例快照给全部订阅者，含失效连接清理与单连接失败告警。 */
    public void pushSnapshot(RegistrySnapshot snapshot) {
        if (!pushEnabled.get() || snapshot == null) {
            return;
        }
        Set<Channel> subscribers =
                subscriptionManager.findSubscribers(snapshot.getServiceName(), snapshot.getGroup());
        if (subscribers.isEmpty()) {
            return;
        }
        metrics.push(snapshot.getServiceName(), subscribers.size());
        ServicePushBody body = toPushBody(snapshot);

        long pushId = pushIdGenerator.getAndIncrement();
        for (Channel channel : subscribers) {
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
        }
        log.info("推送服务变更: service={}, revision={}, epoch={}, term={}, subscribers={}",
                snapshot.getServiceName(),
                snapshot.getRevision(),
                body.getEpoch(),
                generation.term(),
                subscribers.size());
    }

    /** 初次订阅只向发起订阅的连接发送当前快照，避免把老订阅者全部广播一遍。 */
    public void pushSnapshotTo(RegistrySnapshot snapshot, Channel channel) {
        if (!pushEnabled.get() || snapshot == null || channel == null || !channel.isActive()) {
            return;
        }
        metrics.push(snapshot.getServiceName(), 1);
        ServicePushBody body = toPushBody(snapshot);
        long pushId = pushIdGenerator.getAndIncrement();
        channel.writeAndFlush(RoverMessageCodecSupport.push(pushId, body)).addListener(future -> {
            if (!future.isSuccess()) {
                log.warn("初始快照推送失败: service={}, channel={}",
                        snapshot.getServiceName(), channel.remoteAddress(), future.cause());
            }
        });
    }

    private ServicePushBody toPushBody(RegistrySnapshot snapshot) {
        ServicePushBody body = new ServicePushBody();
        body.setServiceName(snapshot.getServiceName());
        body.setGroup(snapshot.getGroup());
        body.setInstances(snapshot.getInstances());
        body.setRevision(snapshot.getRevision());
        body.setEpoch(generation.epoch());
        body.setPushType(PushType.SNAPSHOT.name());
        return body;
    }
}
