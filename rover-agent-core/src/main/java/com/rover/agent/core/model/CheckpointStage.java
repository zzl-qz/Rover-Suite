package com.rover.agent.core.model;

/**
 * 安全恢复点：一次调查可以在哪个阶段边界被安全地接着跑。
 *
 * <p><b>只描述业务阶段，不描述运行时节点。</b>取值的语义是「到这里为止的状态已经确定并持久化」，
 * 而不是「刚才准备干到这里」。因此 {@link #TOOL_COMPLETED} 指的是工具返回、步骤与证据都已落库，
 * 而不是「准备调用工具」——崩溃在调用途中没有新恢复点，Recovery 从上一个点重做那个只读工具。
 *
 * <p>刻意不叫节点名（如 {@code EXECUTE_CAPABILITY}）：人工会话走 ToolLoop、告警走调查图，
 * 两条路径共用这一套取值；框架换掉、图节点重命名都不该影响恢复语义。
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
