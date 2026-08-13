package com.rover.nameserver.core.event.spi.listener;

import com.rover.common.event.EventListener;
import com.rover.nameserver.core.event.model.ChannelInactiveEvent;
import com.rover.nameserver.core.event.support.NameserverChannelSupport;
import com.rover.nameserver.core.event.support.NameserverServices;
import com.rover.nameserver.core.registry.RegistrySnapshot;
import java.util.Set;

/** 连接断开：清订阅，注销该连接绑定的实例并 Push */
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
            return;
        }
        for (String key : bound) {
            String[] parts = key.split("#", 2);
            if (parts.length != 2) {
                continue;
            }
            RegistrySnapshot snapshot = services.getRegistry().unregister(parts[0], parts[1]);
            if (snapshot != null) {
                services.getPushService().pushSnapshot(snapshot);
            }
        }
    }
}
