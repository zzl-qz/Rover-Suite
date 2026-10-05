package com.rover.agent.core.planning;

/** 规划评估结论：继续调查、合成结果或请求澄清。 */
public enum PlanningVerdict {

    /** 还缺关键事实，按新计划再执行一轮 */
    CONTINUE,

    /** 证据已足够（或已无可用步骤），进入结论综合 */
    FINISH,

    /** 缺少必要信息，向用户澄清后再继续 */
    CLARIFY
}