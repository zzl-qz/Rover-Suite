package com.rover.agent.core.event;

/** 任务状态变化通知；任务快照以 AgentTaskRepository 为准。 */
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

    /** 模型思考过程的增量文本，独立于回答正文。 */
    THINKING_DELTA,

    /** 目标无法确定，任务等待用户补充信息。 */
    CLARIFICATION_REQUIRED,

    /** 任务成功结束，结论与证据已落定。 */
    TASK_COMPLETED,

    /** 任务失败，只有错误说明。 */
    TASK_FAILED,

    /** 任务被取消（本阶段尚未接入取消入口，类型先按协议预留）。 */
    TASK_CANCELLED;

    /** 判断终态事件；CLARIFICATION_REQUIRED 非终态，但本次订阅应结束。 */
    public boolean terminal() {
        return this == TASK_COMPLETED || this == TASK_FAILED || this == TASK_CANCELLED;
    }
}