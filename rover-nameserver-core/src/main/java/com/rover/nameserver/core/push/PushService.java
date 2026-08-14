package com.rover.nameserver.core.push;

import com.rover.common.codec.RoverMessageCodecSupport;
import com.rover.common.protocol.ServicePushBody;
import com.rover.nameserver.core.cluster.NameserverGeneration;
import com.rover.nameserver.core.registry.RegistrySnapshot;
import io.netty.channel.Channel;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 服务变更推送：全量快照 + generation.epoch（拒旧/换代用）。
 * epoch 不在这里写死 UUID，统一问 {@link NameserverGeneration}。
 */
@Slf4j
public class PushService {

    private final SubscriptionManager subscriptionManager;
    private final AtomicBoolean pushEnabled;
    private final AtomicLong pushIdGenerator = new AtomicLong(1);
    @Getter
    private final NameserverGeneration generation;

    public PushService(
            SubscriptionManager subscriptionManager,
            boolean pushEnabled,
            NameserverGeneration generation) {
        this.subscriptionManager = subscriptionManager;
        this.pushEnabled = new AtomicBoolean(pushEnabled);
        this.generation = Objects.requireNonNull(generation, "generation");
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
     * 推送服务实例快照
     * @param snapshot
     */
    public void pushSnapshot(RegistrySnapshot snapshot) {
        if (!pushEnabled.get() || snapshot == null) {
            return;
        }
        Set<Channel> subscribers =
                subscriptionManager.findSubscribers(snapshot.getServiceName(), snapshot.getGroup());
        if (subscribers.isEmpty()) {
            return;
        }

        String epoch = generation.epoch();
        ServicePushBody body = new ServicePushBody();
        body.setServiceName(snapshot.getServiceName());
        body.setGroup(snapshot.getGroup());
        body.setInstances(snapshot.getInstances());
        body.setRevision(snapshot.getRevision());
        body.setEpoch(epoch);
        body.setPushType("SNAPSHOT");

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
                epoch,
                generation.term(),
                subscribers.size());
    }
}
