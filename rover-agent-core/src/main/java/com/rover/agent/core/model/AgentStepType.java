package com.rover.agent.core.model;

/**
 * 调查步骤的类型：前端据此把步骤归位到固定阶段，而不依赖中文名称。
 *
 * {@link #TARGET_RESOLUTION} 与 {@link #AI_EXPLANATION} 是本项目在规格给定的六个阶段之外的补充：
 * 前者承载「把自然语言问题解析成调查对象」，后者承载「模型解读」这一既有能力。
 */
public enum AgentStepType {

    /** 解析用户问题指向的资源对象 */
    TARGET_RESOLUTION,

    /** 读取 Gateway 路由 */
    ROUTE_INVESTIGATION,

    /** 读取 Nameserver 实例 */
    INSTANCE_INVESTIGATION,

    /** 读取 Gateway 指标 */
    METRIC_INVESTIGATION,

    /** 读取 Gateway 追踪 */
    TRACE_INVESTIGATION,

    /** 合成假设验证结论 */
    DIAGNOSIS,

    /** 模型解读结论 */
    AI_EXPLANATION
}