package com.rover.nameserver.core.event.support;

import com.rover.nameserver.core.cluster.NameserverGeneration;
import com.rover.nameserver.core.consistency.WriteAckPolicy;
import com.rover.nameserver.core.push.PushService;
import com.rover.nameserver.core.push.SubscriptionManager;
import com.rover.nameserver.core.registry.ServiceRegistry;
import com.rover.nameserver.core.server.NameserverServerOptions;
import java.util.Objects;
import lombok.Getter;

/**
 * Author: Daylight
 * Created: 2026-08-07 09:20:00
 * Description: Nameserver 业务依赖门面，向各协议 Listener 注入注册表、推送与配置等依赖
 */
@Getter
public class NameserverServices {

    private final ServiceRegistry registry;
    private final SubscriptionManager subscriptionManager;
    private final PushService pushService;
    private final WriteAckPolicy writeAckPolicy;
    private final NameserverServerOptions options;
    /** 世代视图：单机=进程 UUID；集群可替换实现 */
    private final NameserverGeneration generation;

    public NameserverServices(
            ServiceRegistry registry,
            SubscriptionManager subscriptionManager,
            PushService pushService,
            WriteAckPolicy writeAckPolicy,
            NameserverServerOptions options,
            NameserverGeneration generation) {
        this.registry = registry;
        this.subscriptionManager = subscriptionManager;
        this.pushService = pushService;
        this.writeAckPolicy = writeAckPolicy;
        this.options = options;
        this.generation = Objects.requireNonNull(generation, "generation");
    }

    /** 协议 epoch：委托 Generation，调用方不要自己 new UUID。 */
    public String getEpoch() {
        return generation.epoch();
    }
}
