package com.rover.agent.core.model;

/**
 * 业务恢复点，记录已完成步骤、证据高水位和已使用预算。
 * resumeStateJson 仅存无法从关系表重建的状态；runtimeNode 仅用于诊断。
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
