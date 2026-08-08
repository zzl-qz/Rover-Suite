package com.rover.nameserver.core.push;

import com.rover.common.protocol.ServicePushBody;
import com.rover.nameserver.client.codec.RoverMessageCodecSupport;
import com.rover.nameserver.core.registry.RegistrySnapshot;
import io.netty.channel.Channel;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: 服务变更推送
 */
@Slf4j
public class PushService {

    private final SubscriptionManager subscriptionManager;
    private final boolean pushEnabled;
    private final AtomicLong pushIdGenerator = new AtomicLong(1);

    public PushService(SubscriptionManager subscriptionManager, boolean pushEnabled) {
        this.subscriptionManager = subscriptionManager;
        this.pushEnabled = pushEnabled;
    }

    public void pushSnapshot(RegistrySnapshot snapshot) {
        if (!pushEnabled || snapshot == null) {
            return;
        }
        Set<Channel> subscribers =
                subscriptionManager.findSubscribers(snapshot.getServiceName(), snapshot.getGroup());
        if (subscribers.isEmpty()) {
            return;
        }

        ServicePushBody body = new ServicePushBody();
        body.setServiceName(snapshot.getServiceName());
        body.setGroup(snapshot.getGroup());
        body.setInstances(snapshot.getInstances());
        body.setRevision(snapshot.getRevision());
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
        log.info("推送服务变更: service={}, revision={}, subscribers={}",
                snapshot.getServiceName(), snapshot.getRevision(), subscribers.size());
    }
}
