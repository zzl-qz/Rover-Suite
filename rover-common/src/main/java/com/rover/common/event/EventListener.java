package com.rover.common.event;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 事件监听器回调
 */
public interface EventListener<E extends Event> {

    /**
     * 事件到达时的回调入口。
     *
     * @param event 事件对象，类型由泛型 E 决定
     */
    void onEvent(E event) throws Exception;
}
