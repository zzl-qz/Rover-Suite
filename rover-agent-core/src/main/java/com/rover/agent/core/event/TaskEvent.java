package com.rover.agent.core.event;

import java.util.Objects;

/**
 * 任务事件的统一信封：所有类型共用一套字段。
 *
 * {@code eventId} 由任务单调递增分配，订阅者据此判断快照已覆盖到哪个事件；{@code payload} 的形状随类型而定。
 */
public record TaskEvent(long eventId, String taskId, TaskEventType type, long timestampMillis, Object payload) {

    public TaskEvent {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(type, "type");
    }
}