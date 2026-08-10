package com.rover.common.event;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义事件监听器的回调契约
 *
 * 这个接口是什么：事件监听器回调接口(函数式回调)。
 * 核心职责：约束监听器在事件到达时必须实现的回调方法，泛型 T 声明监听的事件类型。
 * 被谁用：订阅 EventBus 的组件，以匿名类或 lambda 形式实现。
 */
public interface EventListener<T> {

    /**
     * 事件到达时的回调入口。
     *
     * @param event 事件对象，具体类型由泛型 T 决定
     */
    void onEvent(T event);
}
