package com.rover.nameserver.core.event.listener;

import com.rover.common.event.EventListener;
import com.rover.nameserver.core.event.model.ChannelInactiveEvent;
import com.rover.nameserver.core.event.support.NameserverChannelSupport;
import com.rover.nameserver.core.event.support.NameserverServices;
import com.rover.nameserver.core.event.support.NameserverTrace;
import com.rover.nameserver.core.registry.RegistrySnapshot;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 09:55:00
 * Description: 处理连接断开：清理订阅并注销该连接绑定的实例、触发推送
 */
@Slf4j
public class ChannelInactiveListener implements EventListener<ChannelInactiveEvent> {

    private final NameserverServices services;

    public ChannelInactiveListener(NameserverServices services) {
        this.services = services;
    }

    @Override
    public void onEvent(ChannelInactiveEvent event) {
        services.getSubscriptionManager().removeChannel(event.getChannel());
        Set<String> bound = NameserverChannelSupport.takeBoundInstances(event.getChannel());
        if (bound == null || bound.isEmpty()) {
            log.debug("action=channel-inactive-cleanup, remote={}, bound=0",
                    NameserverTrace.remote(event.getChannel()));
            return;
        }
        int removed = 0;
        for (String key : bound) {
            String[] parts = key.split("#", 2);
            if (parts.length != 2) {
                continue;
            }
            RegistrySnapshot snapshot = services.getRegistry().unregister(parts[0], parts[1]);
            if (snapshot != null) {
                services.getPushService().pushSnapshot(snapshot);
                removed++;
                log.info("action=channel-inactive-unregister, remote={}, serviceName={}, instanceId={}, revision={}",
                        NameserverTrace.remote(event.getChannel()),
                        parts[0],
                        parts[1],
                        snapshot.getRevision());
            }
        }
        log.info("action=channel-inactive-done, remote={}, unbound={}",
                NameserverTrace.remote(event.getChannel()), removed);
    }
}
