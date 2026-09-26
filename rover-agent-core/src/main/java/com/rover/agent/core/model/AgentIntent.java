package com.rover.agent.core.model;

/**
 * 用户意图：这条消息到底想让 Agent 干什么。
 *
 * 意图与目标（{@link ResourceTarget}）是两件事：「order-service 现在有几个实例？」与
 * 「order-service 为什么 502？」目标相同而意图不同（状态查询 vs 故障调查），执行路径也不同。
 * 本阶段真正实现 {@link #QUERY_STATE}、{@link #INVESTIGATE}、{@link #EXPLAIN}、
 * {@link #ACTION_REQUEST} 四类；其余意图只识别、不假装执行。
 */
public enum AgentIntent {

    /** 状态查询：问当前事实（QPS、实例数、路由指向），只需少量只读能力，不做故障调查 */
    QUERY_STATE,

    /** 故障调查：问「为什么失败」，需要规划只读调查步骤并给出根因结论 */
    INVESTIGATE,

    /** 解释/说明：说明系统能力，或基于已有上下文与证据做总结解释 */
    EXPLAIN,

    /** 处置请求：要求对某个对象执行操作；本阶段只产出不可执行的处置计划 */
    ACTION_REQUEST,

    /** 定时巡检：要求创建周期性任务；本阶段尚未接入调度与通知，只如实回复未开放 */
    CREATE_INSPECTION,

    /** 知识检索：问文档/知识库内容；本阶段尚未接入检索，只如实回复未开放 */
    KNOWLEDGE_QUERY,

    /** 未能识别意图；由编排层按可解析的资源对象兜底判断或向用户澄清 */
    UNKNOWN
}