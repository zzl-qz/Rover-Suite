package com.rover.agent.core.event;

/**
 * 由独立派发线程调用的任务事件订阅者。
 * 允许网络 IO，但不得等待后续事件或向外抛出异常。
 */
@FunctionalInterface
public interface TaskEventSubscriber {

    void onEvent(TaskEvent event);
}