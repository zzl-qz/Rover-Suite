package com.rover.agent.core.model;

/**
 * 任务步骤的类型：前端据此把步骤归位到固定阶段，而不依赖中文名称。
 *
 * 除规格给定的数据采集与结论阶段外，本项目还有几类补充阶段：
 * {@link #PLANNING}（规划只读步骤）、{@link #TARGET_RESOLUTION}（把自然语言问题解析成调查对象）、
 * {@link #ANSWER}（直接回答状态查询）、{@link #MEMORY}（读取或写入长期记忆）与
 * {@link #AI_EXPLANATION}（模型解读）。
 */
public enum AgentStepType {

    /** 规划只读调查步骤 */
    PLANNING,

    /** 直接回答状态查询 */
    ANSWER,

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

    /** 读取 Gateway / Nameserver 生效配置 */
    CONFIG_INVESTIGATION,

    /** 读取注册中心事件 */
    EVENT_INVESTIGATION,

    /** 读取落盘历史日志 */
    LOG_INVESTIGATION,

    /** 检索运维知识库 */
    KNOWLEDGE_INVESTIGATION,

    /** 合成假设验证结论 */
    DIAGNOSIS,

    /** 读取或写入长期记忆 */
    MEMORY,

    /** 模型解读结论 */
    AI_EXPLANATION
}
