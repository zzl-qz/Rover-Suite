package com.rover.agent.core.model;

/** 事件状态：一个被调查的真实问题在其生命周期中所处的阶段。 */
public enum IncidentStatus {

    /** 已登记，尚未开始调查 */
    OPEN,

    /** 正在调查 */
    INVESTIGATING,

    /** 已得出结论 */
    RESOLVED
}