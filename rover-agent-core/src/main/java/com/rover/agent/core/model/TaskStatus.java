package com.rover.agent.core.model;

/** 调查任务状态。 */
public enum TaskStatus {

    /** 已创建，等待执行 */
    PENDING,

    /** 调查进行中 */
    RUNNING,

    /** 调查完成，结论与证据已产出 */
    COMPLETED,

    /** 调查失败，只有错误说明没有结论 */
    FAILED,

    /** 被主动取消，尚未接入取消入口 */
    CANCELLED
}