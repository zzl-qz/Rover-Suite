package com.rover.agent.core.event;

import java.util.Objects;

/**
 * 任务事件的统一信封：所有类型共用同一套字段，不让每种事件各自随意返回 JSON。
 *
 * {@code eventId} 由任务在状态锁内单调递增分配，因此同一任务的订阅者收到的事件顺序
 * 与状态变更顺序一致，也可以用它判断快照已经覆盖到哪个事件（见 {@link TaskSnapshot}）。
 *
 * {@code payload} 的具体形状随 {@link #type()} 而定，取值都是可序列化的简单值
 * （字符串、枚举、记录、列表、Map），保证核心模块不依赖任何 JSON 库：
 * <ul>
 *   <li>{@link TaskEventType#SNAPSHOT}：{@link TaskSnapshot}；</li>
 *   <li>{@link TaskEventType#TASK_STARTED} / {@link TaskEventType#TASK_COMPLETED} 等状态事件：{@code {"status": TaskStatus}}；</li>
 *   <li>{@link TaskEventType#STEP_STARTED} / {@code STEP_COMPLETED} / {@code STEP_FAILED}：{@code {"step": Step}}；</li>
 *   <li>{@link TaskEventType#EVIDENCE_ADDED}：{@code {"evidence": List<Evidence>, "count": int}}；</li>
 *   <li>{@link TaskEventType#ANALYSIS_DELTA}：{@code {"text": String}}；</li>
 *   <li>{@link TaskEventType#CLARIFICATION_REQUIRED}：{@code {"clarification": String}}；</li>
 *   <li>{@link TaskEventType#TASK_COMPLETED}：{@code {"status": TaskStatus, "summary": String, "confidence": Confidence}}；</li>
 *   <li>{@link TaskEventType#TASK_FAILED}：{@code {"status": TaskStatus, "error": String}}。</li>
 * </ul>
 *
 * @param eventId         任务内单调递增的事件序号，从 1 开始
 * @param taskId          事件所属任务
 * @param type            事件类型
 * @param timestampMillis 事件产生时刻
 * @param payload         事件负载，形状随类型而定
 */
public record TaskEvent(long eventId, String taskId, TaskEventType type, long timestampMillis, Object payload) {

    public TaskEvent {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(type, "type");
    }
}