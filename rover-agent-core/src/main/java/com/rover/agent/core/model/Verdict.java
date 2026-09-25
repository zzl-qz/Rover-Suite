package com.rover.agent.core.model;

/** 假设验证结论：只依据本次调查已采集到的只读事实判定。 */
public enum Verdict {

    /** 已被证据确认 */
    CONFIRMED,

    /** 已被证据排除 */
    REJECTED,

    /** 证据不足，无法验证 */
    UNKNOWN
}