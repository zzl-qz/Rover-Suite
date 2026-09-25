package com.rover.agent.core.model;

/** 调查步骤状态。 */
public enum StepStatus {

    /** 步骤执行中 */
    RUNNING,

    /** 步骤完成并已产出该步结果说明 */
    COMPLETED,

    /** 步骤失败，降级为「证据不足」继续后续步骤 */
    FAILED
}