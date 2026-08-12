package com.rover.nameserver.core.event;

import com.rover.common.event.Event;
import com.rover.common.event.EventBus;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-12 00:00:00
 * Description: Nameserver 事件总线启动加载器
 *
 * 这个类是什么：把通用 EventBus 装配进 Nameserver 生命周期的启动入口。
 * 核心职责：创建 EventBus，start() 时通过 SPI 加载全部监听器，publish() 转发事件，shutdown() 释放线程池。
 * 被谁用：NameserverTcpServer 在构造/启动/关闭时调用；未来请求分发层通过 publish() 发布事件。
 */
@Slf4j
public class EventBusBootstrap {

    private final EventBus eventBus;

    /**
     * 构造启动加载器。
     *
     * @param name 事件总线名称，用于派发线程命名
     */
    public EventBusBootstrap(String name) {
        this.eventBus = new EventBus(name);
    }

    /** 启动：SPI 加载全部 EventListener 实现并注册到对应事件类型。 */
    public void start() {
        eventBus.init();
        log.info("Nameserver 事件总线已启动");
    }

    /** 发布事件：按事件类型异步派发给监听器。 */
    public void publish(Event event) {
        eventBus.publish(event);
    }

    /** 同步发布事件：在调用线程顺序派发，供顺序敏感的主链路使用。 */
    public void publishSync(Event event) {
        eventBus.publishSync(event);
    }

    /** 关闭：停止派发线程池。 */
    public void shutdown() {
        eventBus.shutdown();
    }
}
