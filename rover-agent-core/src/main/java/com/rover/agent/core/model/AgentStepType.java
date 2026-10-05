package com.rover.agent.core.model;

/** 任务步骤类型，用于前端阶段展示。 */
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

    /** 生成待人工审批的变更计划（只登记建议，不修改任何生产状态） */
    ACTION_PROPOSAL,

    /** 读取或写入长期记忆 */
    MEMORY,

    /** 模型解读结论 */
    AI_EXPLANATION
}
