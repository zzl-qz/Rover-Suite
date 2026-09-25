package com.rover.agent.core.model;

/** 调查来源：主动提问、告警接入或定时巡检。 */
public enum IncidentOrigin {

    /** 用户主动发起调查 */
    USER,

    /** 告警系统触发 */
    ALERT,

    /** 定时巡检触发 */
    INSPECTION
}