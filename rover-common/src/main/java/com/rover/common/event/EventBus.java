package com.rover.common.event;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义事件发布与监听订阅能力
 *
 * 这个接口是什么：进程内事件总线的统一契约(观察者模式)。
 * 核心职责：解耦事件的「生产者(发布)」与「消费者(订阅)」，
 * 使服务变更、配置变更等消息能以事件方式在模块间流转。
 * 被谁用：实现方为各模块自带的事件总线(如网关/注册中心内部实现)；
 * 调用方为需要发布事件或监听事件的组件。
 */
public interface EventBus {

    /**
     * 发布事件，广播给所有已订阅的监听器。
     *
     * @param event 待发布的事件，不可为 null
     */
    void publish(Event event);

    /**
     * 注册事件监听器。
     *
     * @param listener 监听器；具体监听哪类事件由泛型决定
     */
    void subscribe(EventListener<? extends Event> listener);
}
