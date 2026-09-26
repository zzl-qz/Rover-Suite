package com.rover.agent.core.model;

/** 会话状态。 */
public enum SessionStatus {

    /** 进行中：可以继续追问 */
    ACTIVE,

    /** 已归档：只可回看，不再接受新的调查 */
    ARCHIVED
}