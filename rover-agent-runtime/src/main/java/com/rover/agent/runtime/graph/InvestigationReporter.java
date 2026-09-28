package com.rover.agent.runtime.graph;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.model.CheckpointStage;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.planning.InvestigationPlan;
import java.util.List;

/**
 * 调查过程上报口：除步骤进度外，还要把「这次打算查什么、实际用到了哪些能力、已经取到哪些证据」
 * 写进任务快照，并在安全边界上推进恢复点。
 *
 * <p>计划与已用能力不是过程日志，而是结果的一部分：用户要能看到 Agent 为什么查这几项、
 * 以及结论建立在哪几个只读能力上。因此它们由任务对象持有并随轮询快照一起暴露。
 *
 * <p>{@link #recordEvidence} 与 {@link #checkpoint} 是配套的一对：工具执行完先把证据交上来，
 * 再在同一个恢复点上推进高水位——两者之间崩溃，等于这个工具没跑过，重来一次不会重复也无害。
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
