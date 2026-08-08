/**
 * 作者：Daylight
 * 创建时间：2026-08-08 10:34:00
 * 描述：定义事件监听器的回调契约
 */
package com.rover.common.event;

public interface EventListener<T> {

    void onEvent(T event);
}
