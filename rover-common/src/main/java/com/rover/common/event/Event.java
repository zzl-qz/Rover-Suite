package com.rover.common.event;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义 Rover 事件模型的基础标记接口
 *
 * 这个接口是什么：Rover 事件体系的统一类型标记。
 * 核心职责：约束所有可经 EventBus 发布/订阅的事件对象实现该接口，
 * 使 EventBus 能按类型泛型化处理各类事件。
 * 被谁用：ServiceChangeEvent、ConfigChangeEvent 等具体事件实现者。
 */
public interface Event {
}
