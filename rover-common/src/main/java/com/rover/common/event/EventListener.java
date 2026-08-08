package com.rover.common.event;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义事件监听器的回调契约
 */
public interface EventListener<T> {

    void onEvent(T event);
}
