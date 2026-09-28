package com.rover.agent.core.model;

/**
 * 一个安全恢复点：调查可以从此处接着继续，而不必重跑已经落库的外部观测。
 *
 * <p>它是<b>业务级恢复契约</b>，不是某个 Agent 框架的内部状态快照——框架换掉、图节点改名，
 * 恢复语义都不变。因此这里存的是「高水位」而不是「清单」：
 *
 * <ul>
 *   <li>{@code lastStepSequence} / {@code lastEvidenceSequence}：已经可靠完成的步骤与证据条数。
 *       具体是哪些步骤、哪些证据，从 {@code agent_step} / {@code agent_evidence} 按序号读回来即可，
 *       不在这里再复制一份 ID 列表。</li>
 *   <li>{@code roundNo} / {@code toolCallCount}：规划轮数与只读调用次数，恢复时接着算预算，
 *       不让一次重启把「调用上限」清零。</li>
 *   <li>{@code resumeStateJson}：只放无法从关系表重建的小块运行态；能从库里查出来的东西不写在这里。</li>
 *   <li>{@code runtimeNode}：运行时节点名，仅供诊断——恢复流程<b>不</b>依赖它。</li>
 * </ul>
 *
 * @param checkpointId          恢复点标识
 * @param taskId                所属任务
 * @param sequenceNo            该任务内的恢复点序号，从 1 递增
 * @param stage                 所处的业务阶段
 * @param roundNo               规划轮数（没有 Planner Loop 的路径为 0）
 * @param toolCallCount         已完成的只读调用次数
 * @param lastStepSequence      已可靠完成的步骤条数（高水位）
 * @param lastEvidenceSequence  已可靠落库的证据条数（高水位）
 * @param resumeStateJson       无法从关系表重建的运行态；没有则为空串
 * @param runtimeNode           运行时节点名（辅助诊断，可为空）
 * @param createdAtMillis       落库时间
 */
public record AgentCheckpoint(String checkpointId, String taskId, int sequenceNo, CheckpointStage stage,
                              int roundNo, int toolCallCount, int lastStepSequence, int lastEvidenceSequence,
                              String resumeStateJson, String runtimeNode, long createdAtMillis) {

    public AgentCheckpoint {
        resumeStateJson = resumeStateJson == null ? "" : resumeStateJson;
        runtimeNode = runtimeNode == null ? "" : runtimeNode;
    }

    /** 这个恢复点是否已经落进过证据（用于诊断「能恢复到哪」）。 */
    public boolean hasEvidence() {
        return lastEvidenceSequence > 0;
    }

    /** 一句话说明恢复点位置：写进中断任务的错误说明，让人一眼看出能接着跑到哪。 */
    public String describe() {
        StringBuilder text = new StringBuilder(stage.name())
                .append("（第 ").append(roundNo).append(" 轮，已调用 ").append(toolCallCount).append(" 次")
                .append("，步骤 ").append(lastStepSequence).append(" 条，证据 ").append(lastEvidenceSequence)
                .append(" 条）");
        return text.toString();
    }
}
