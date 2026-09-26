package com.rover.agent.core.event;

/**
 * 任务事件类型：状态变化的通知，不是事实来源。
 *
 * 事实来源始终是 {@code AgentTaskRepository} 里的任务快照——任何事件表达的信息，
 * {@code GET /api/agent/tasks/{taskId}} 都必须能查到。事件只是让观察者（SSE、前端）
 * 不必轮询也能及时看到变化；丢事件、断连接都不影响任务本身继续执行。
 */
public enum TaskEventType {

    /** 订阅/重连时先补发的全量快照：任务视图 + 已产生的解读文本 + 已覆盖的事件序号。 */
    SNAPSHOT,

    /** 任务已登记（PENDING）。 */
    TASK_CREATED,

    /** 任务开始执行（RUNNING）。 */
    TASK_STARTED,

    /** 一步开始执行。 */
    STEP_STARTED,

    /** 一步成功结束。 */
    STEP_COMPLETED,

    /** 一步失败。 */
    STEP_FAILED,

    /** 调查证据已产出（证据只在结论产出时一次性落定）。 */
    EVIDENCE_ADDED,

    /** 模型解读的增量文本。 */
    ANALYSIS_DELTA,

    /** 目标无法确定，任务等待用户补充信息。 */
    CLARIFICATION_REQUIRED,

    /** 任务成功结束，结论与证据已落定。 */
    TASK_COMPLETED,

    /** 任务失败，只有错误说明。 */
    TASK_FAILED,

    /** 任务被取消（本阶段尚未接入取消入口，类型先按协议预留）。 */
    TASK_CANCELLED;

    /**
     * 是否为终态事件：任务状态不会再变化，订阅者收到后可以结束观察。
     *
     * {@link #CLARIFICATION_REQUIRED} 不算终态事件：任务停在澄清点等待用户补充，
     * 之后可能以新的任务继续，但当前这次观察应结束——由订阅者自行关闭连接。
     */
    public boolean terminal() {
        return this == TASK_COMPLETED || this == TASK_FAILED || this == TASK_CANCELLED;
    }
}