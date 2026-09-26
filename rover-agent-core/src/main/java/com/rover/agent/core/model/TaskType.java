package com.rover.agent.core.model;

/**
 * 任务类型：一次提问最终以什么形态执行，前端据此选择展示方式，而不是靠问题文本猜。
 *
 * 规格要求区分 QUERY / INVESTIGATION / ACTION_PLAN 三类；这里额外补两个诚实的形态：
 * {@link #EXPLAIN}（能力说明与上下文解释，不采新证据或只做轻量取数）与
 * {@link #UNSUPPORTED}（意图已识别但能力未开放，明确回复而不是硬走调查）。
 */
public enum TaskType {

    /** 状态查询：少量只读能力直接回答 */
    QUERY,

    /** 故障调查：规划并执行只读调查步骤，产出假设验证结论 */
    INVESTIGATION,

    /** 处置计划：预检后产出受控处置计划，本阶段不执行 */
    ACTION_PLAN,

    /** 解释说明：能力清单或基于上下文的解释 */
    EXPLAIN,

    /** 已识别但未开放的能力请求 */
    UNSUPPORTED
}