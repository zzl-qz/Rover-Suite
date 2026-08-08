package com.rover.common.event;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义事件发布与监听订阅能力
 */
public interface EventBus {

    void publish(Event event);

    void subscribe(EventListener<? extends Event> listener);
}
