package com.rover.agent.core.planning;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.model.ResourceTarget;

/**
 * 调查计划中的只读能力步骤，能力须在注册表中声明。
 *
 * @param capability 能力标识
 * @param reason     选择该能力的依据（展示给用户，说明「为什么查这个」）
 * @param target     作用对象；未指定时按请求目标补齐
 * @param required   是否属于必查步骤：模型规划漏掉时由确定性校验补齐
 */
public record PlannedStep(AgentCapability capability, String reason, ResourceTarget target, boolean required) {

    public PlannedStep {
        capability = capability == null ? AgentCapability.ROUTE_QUERY : capability;
        reason = reason == null ? "" : reason.trim();
        target = target == null ? ResourceTarget.unknown() : target;
    }

    /** 必查步骤。 */
    public static PlannedStep required(AgentCapability capability, String reason, ResourceTarget target) {
        return new PlannedStep(capability, reason, target, true);
    }

    /** 补充步骤。 */
    public static PlannedStep optional(AgentCapability capability, String reason, ResourceTarget target) {
        return new PlannedStep(capability, reason, target, false);
    }
}