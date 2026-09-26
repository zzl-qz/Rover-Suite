package com.rover.agent.core.model;

/** 会话消息的发出方。 */
public enum MessageRole {

    /** 用户提问或补充条件 */
    USER,

    /** Agent 回复 */
    AGENT,

    /** 系统注入的上下文说明 */
    SYSTEM
}