package com.rover.agent.runtime.graph;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.model.CheckpointStage;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.planning.InvestigationPlan;
import java.util.List;

/**
 * 将调查计划、已用能力和证据写入任务快照，并推进安全恢复点。
 * 步骤完成、证据交付后才能记录恢复点。
 */
public interface InvestigationReporter extends StepSink {

    /** 本轮调查计划（含每步依据）；计划为空表示本轮没有可执行步骤。 */
    void reportPlan(InvestigationPlan plan);

    /** 某个能力已被执行或按事实跳过（跳过同样算处理过，不再重复规划）。 */
    void reportCapabilityExecuted(AgentCapability capability);

    /** 本次执行新取到的证据：追加到任务上，随下一个恢复点一起落库。 */
    void recordEvidence(List<Evidence> evidence);

    /**
     * 推进一个安全恢复点：调用方保证此刻的状态已经确定（步骤已终态、证据已交付）。
     *
     * @param stage        业务阶段
     * @param roundNo      规划轮数（无 Planner Loop 的路径传 0）
     * @param toolCallCount 已完成的只读调用次数
     * @param runtimeNode  运行时节点名，仅作诊断线索，不参与恢复判定
     */
    void checkpoint(CheckpointStage stage, int roundNo, int toolCallCount, String runtimeNode);
}
