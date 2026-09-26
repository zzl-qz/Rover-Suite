package com.rover.agent.core.model;

/**
 * 事件严重度。
 *
 * 本轮不推断严重度，新建事件一律为 {@link #UNKNOWN}；分级能力随巡检与告警接入一起实现。
 */
public enum IncidentSeverity {

    /** 尚未判定 */
    UNKNOWN,

    /** 影响可忽略 */
    LOW,

    /** 影响部分请求 */
    MEDIUM,

    /** 影响核心链路 */
    HIGH,

    /** 服务不可用 */
    CRITICAL
}