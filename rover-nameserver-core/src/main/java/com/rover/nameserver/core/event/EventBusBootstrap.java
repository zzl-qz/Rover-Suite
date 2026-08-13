package com.rover.nameserver.core.event;

import com.rover.common.event.Event;
import com.rover.common.event.EventBus;
import com.rover.common.event.EventListener;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-12 00:00:00
 * Description: Nameserver 事件总线启动加载器
 *
 * 把通用 EventBus 挂进 Nameserver 生命周期：
 * start=SPI 加载，publish/publishSync 转发，register 便于测试，shutdown 释放线程池。
 */
@Slf4j
public class EventBusBootstrap {

    @Getter
    private final EventBus eventBus;

    /**
     * @param name 事件总线名称，用于派发线程命名
     */
    public EventBusBootstrap(String name) {
        this.eventBus = new EventBus(name);
    }

    /** 启动：SPI 加载全部 EventListener */
    public void start() {
        eventBus.init();
        log.info("Nameserver 事件总线已启动");
    }

    /** 异步发布：副作用（审计/指标等），失败不挡主链路 */
    public void publish(Event event) {
        eventBus.publish(event);
    }

    /** 同步发布：fail-fast，供顺序敏感场景 */
    public void publishSync(Event event) {
        eventBus.publishSync(event);
    }

    /** 编程注册监听器（单测或启动时补充） */
    public void register(EventListener<?> listener) {
        eventBus.register(listener);
    }

    /** 关闭线程池；之后发布会被忽略 */
    public void shutdown() {
        eventBus.shutdown();
    }
}
