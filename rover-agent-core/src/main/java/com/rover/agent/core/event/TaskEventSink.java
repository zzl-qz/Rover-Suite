package com.rover.agent.core.event;

/**
 * 任务事件发布口：运行层把状态变化投递到这里，不关心谁在观察，也不做任何网络 IO。
 *
 * 实现必须是非阻塞的：发布发生在任务状态锁内（保证事件顺序与状态变更顺序一致），
 * 慢订阅者或写阻塞只能丢弃它自己的事件，绝不能拖慢模型调用与 Agent Worker。
 */
@FunctionalInterface
public interface TaskEventSink {

    void publish(TaskEvent event);
}