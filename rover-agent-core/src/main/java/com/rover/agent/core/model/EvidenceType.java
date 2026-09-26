package com.rover.agent.core.model;

/**
 * 证据种类。
 *
 * 本轮只会产生 {@link #ROUTE}、{@link #INSTANCE}、{@link #METRIC}、{@link #TRACE} 四类；
 * 其余取值只为后续接入预留，不会凭空产出对应证据。
 */
public enum EvidenceType {

    /** Gateway 路由配置 */
    ROUTE,

    /** Nameserver 实例注册与健康状态 */
    INSTANCE,

    /** Gateway 流量与拒绝计数 */
    METRIC,

    /** Gateway 抽样追踪 */
    TRACE,

    /** 应用或 Gateway 日志 */
    LOG,

    /** 事件与告警 */
    EVENT,

    /** 配置项 */
    CONFIG,

    /** 知识库条目 */
    KNOWLEDGE
}