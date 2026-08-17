package com.rover.common.event;

/**
 * Author: Daylight
 * Created: 2026-08-17 09:40:00
 * Description: 需要按 key 串行处理的事件（例如同一 Netty Channel 上的注册与断线）
 */
public interface OrderedEvent {

    /**
     * 同一 key 的事件按发布顺序串行执行。
     * 返回 null 表示不串行，走普通线程池并发分发。
     */
    Object orderKey();
}
