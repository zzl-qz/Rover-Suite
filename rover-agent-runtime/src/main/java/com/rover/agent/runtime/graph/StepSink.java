package com.rover.agent.runtime.graph;

import com.rover.agent.core.model.StepStatus;

/** 调查节点上报步骤进度，使前端可以实时看到调查过程。 */
public interface StepSink {

    void step(String name, StepStatus status, String detail);
}