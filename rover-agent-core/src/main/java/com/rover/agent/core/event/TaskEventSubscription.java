package com.rover.agent.core.event;

/**
 * 订阅句柄：客户端断开、连接超时或出错时调用 {@link #cancel()} 退订。
 *
 * 取消是幂等的；取消后不会再有任何事件投递给对应的 {@link TaskEventSubscriber}。
 */
@FunctionalInterface
public interface TaskEventSubscription {

    /** 退订；重复调用无副作用。 */
    void cancel();

    /** 空句柄：任务不需要观察时使用，避免调用方到处判空。 */
    TaskEventSubscription NONE = () -> { };
}