package com.rover.nameserver.core.event;

import com.rover.common.event.Event;
import com.rover.common.event.EventBus;
import com.rover.common.event.EventListener;
import com.rover.nameserver.core.event.listener.ChannelInactiveListener;
import com.rover.nameserver.core.event.listener.HeartbeatListener;
import com.rover.nameserver.core.event.listener.QueryListener;
import com.rover.nameserver.core.event.listener.RegisterListener;
import com.rover.nameserver.core.event.listener.SubscribeListener;
import com.rover.nameserver.core.event.listener.UnregisterListener;
import com.rover.nameserver.core.event.listener.UnsubscribeListener;
import com.rover.nameserver.core.event.support.NameserverServices;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-12 00:00:00
 * Description: Nameserver 事件总线装配：显式 register 各协议 Listener
 */
@Getter
@Slf4j
public class EventBusBootstrap {

    private final EventBus eventBus;

    public EventBusBootstrap(String name) {
        this.eventBus = new EventBus(name);
    }

    /** 注册全部协议 Listener（带依赖，不用盲 SPI new），再 init 加载无依赖旁路 SPI。 */
    public void start(NameserverServices services) {
        // 需要显式注入依赖的协议 Listener
        eventBus.register(new RegisterListener(services));
        eventBus.register(new UnregisterListener(services));
        eventBus.register(new HeartbeatListener(services));
        eventBus.register(new QueryListener(services));
        eventBus.register(new SubscribeListener(services));
        eventBus.register(new UnsubscribeListener(services));
        eventBus.register(new ChannelInactiveListener(services));
        // 无依赖的旁路 SPI Listener 由 EventBus 自动加载
        eventBus.init();
        log.info("Nameserver 事件总线已启动，协议 Listener 已注册");
    }

    public void publish(Event event) {
        eventBus.publish(event);
    }

    public void register(EventListener<?> listener) {
        eventBus.register(listener);
    }

    public void shutdown() {
        eventBus.shutdown();
    }
}
