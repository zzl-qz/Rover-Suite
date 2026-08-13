package com.rover.nameserver.core.event.support;

import com.rover.nameserver.core.consistency.WriteAckPolicy;
import com.rover.nameserver.core.push.PushService;
import com.rover.nameserver.core.push.SubscriptionManager;
import com.rover.nameserver.core.registry.ServiceRegistry;
import com.rover.nameserver.core.server.NameserverServerOptions;
import lombok.Getter;

/**
 * Author: Daylight
 * Created: 2026-08-13 00:00:00
 * Description: Nameserver 业务依赖门面，注入给各协议 Listener
 */
@Getter
public class NameserverServices {

    private final ServiceRegistry registry;
    private final SubscriptionManager subscriptionManager;
    private final PushService pushService;
    private final WriteAckPolicy writeAckPolicy;
    private final NameserverServerOptions options;

    public NameserverServices(
            ServiceRegistry registry,
            SubscriptionManager subscriptionManager,
            PushService pushService,
            WriteAckPolicy writeAckPolicy,
            NameserverServerOptions options) {
        this.registry = registry;
        this.subscriptionManager = subscriptionManager;
        this.pushService = pushService;
        this.writeAckPolicy = writeAckPolicy;
        this.options = options;
    }
}
