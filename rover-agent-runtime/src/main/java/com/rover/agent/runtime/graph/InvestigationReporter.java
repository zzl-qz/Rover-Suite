package com.rover.agent.runtime.graph;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.planning.InvestigationPlan;

/**
 * 调查过程上报口：除步骤进度外，还要把「这次打算查什么、实际用到了哪些能力」写进任务快照。
 *
 * 计划与已用能力不是过程日志，而是结果的一部分：用户要能看到 Agent 为什么查这几项、
 * 以及结论建立在哪几个只读能力上。因此它们由任务对象持有并随轮询快照一起暴露。
 */
public interface InvestigationReporter extends StepSink {

    /** 本轮调查计划（含每步依据）；计划为空表示本轮没有可执行步骤。 */
    void reportPlan(InvestigationPlan plan);

    /** 某个能力已被执行或按事实跳过（跳过同样算处理过，不再重复规划）。 */
    void reportCapabilityExecuted(AgentCapability capability);
}