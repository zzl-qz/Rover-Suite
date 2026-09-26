package com.rover.agent.core.event;

/**
 * 任务事件订阅者：由事件总线的独立派发线程调用。
 *
 * 因此这里允许做慢操作（例如把事件写进 HTTP 响应），但实现不能阻塞等待自己尚未收到的
 * 后续事件，也不能抛异常——错误只影响本次订阅，必须由实现自己收敛。
 */
@FunctionalInterface
public interface TaskEventSubscriber {

    void onEvent(TaskEvent event);
}