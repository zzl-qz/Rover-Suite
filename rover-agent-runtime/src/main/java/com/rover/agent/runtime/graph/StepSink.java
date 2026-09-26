package com.rover.agent.runtime.graph;

import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.StepStatus;

/**
 * 调查节点上报步骤进度，使前端可以实时看到调查过程。
 *
 * 上报方只给「阶段类型 + 展示名 + 状态 + 说明」，步骤 ID、所属任务与时间戳由接收方补齐。
 */
public interface StepSink {

    void step(AgentStepType type, String name, StepStatus status, String detail);
}