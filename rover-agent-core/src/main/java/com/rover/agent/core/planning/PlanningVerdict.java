package com.rover.agent.core.planning;

/**
 * 一轮评估的结论：继续下一轮只读调查、结束并进入综合，或向用户澄清。
 *
 * 这是动态调查的停止条件载体——循环不是「跑满轮数」，而是「评估认为证据够了就停」。
 */
public enum PlanningVerdict {

    /** 还缺关键事实，按新计划再执行一轮 */
    CONTINUE,

    /** 证据已足够（或已无可用步骤），进入结论综合 */
    FINISH,

    /** 缺少必要信息，向用户澄清后再继续 */
    CLARIFY
}