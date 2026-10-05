package com.rover.agent.core.model;

/**
 * 已完成并持久化的业务阶段，用于任务恢复，不依赖运行时节点名。
 * TOOL_COMPLETED 表示工具结果、步骤与证据均已落库。
 */
public enum CheckpointStage {

    /** 任务开始执行（状态与目标已确定）。 */
    STARTED,

    /** 本轮调查计划已产出并落库（含「本轮没有可执行步骤」的空计划）。 */
    PLANNED,

    /** 一个只读工具已执行完成，且它的步骤与证据都已持久化。 */
    TOOL_COMPLETED,

    /** 本轮证据评估完成，已决定「继续规划 / 澄清 / 出结论」。 */
    ROUND_EVALUATED,

    /** 结论已合成并落库（模型解读或规则结论）。 */
    SYNTHESIS_COMPLETED,

    /** 任务进入终态，结论已提交。 */
    COMPLETED
}
