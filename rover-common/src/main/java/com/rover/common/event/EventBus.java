/**
 * 作者：Daylight
 * 创建时间：2026-08-08 10:34:00
 * 描述：定义事件发布与监听订阅能力
 */
package com.rover.common.event;

public interface EventBus {

    void publish(Event event);

    void subscribe(EventListener<? extends Event> listener);
}
