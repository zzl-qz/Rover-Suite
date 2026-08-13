/**
 * Author: Daylight
 * Created: 2026-08-12 00:00:00
 * Description: 进程内轻量事件总线（aero-mq 同构：Handler 转 Event，Listener 做业务）
 *
 * EventBus 只负责异步精确分发；业务写在各模块的 EventListener 里。
 */
package com.rover.common.event;
