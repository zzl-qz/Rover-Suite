package com.rover.agent.core.capability;

import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.TargetType;
import java.util.List;

/**
 * 能力的注册描述：告诉 Agent 当前 Rover 到底有什么能力。
 *
 * {@code available} 是诚实的开关：没有数据适配器的能力照样注册，但 Planner 不能选择它们。
 */
public record CapabilityDescriptor(AgentCapability id, String name, String description, CapabilityRisk risk,
                                   List<TargetType> supportedTargetTypes, boolean available,
                                   AgentStepType stepType, String stepName) {

    public CapabilityDescriptor {
        id = id == null ? AgentCapability.ROUTE_QUERY : id;
        name = name == null ? id.name() : name.trim();
        description = description == null ? "" : description.trim();
        risk = risk == null ? CapabilityRisk.WRITE : risk;
        supportedTargetTypes = supportedTargetTypes == null ? List.of() : List.copyOf(supportedTargetTypes);
        stepType = stepType == null ? AgentStepType.ANSWER : stepType;
        stepName = stepName == null || stepName.isBlank() ? name : stepName.trim();
    }

    /** 该能力是否接受这种目标类型。 */
    public boolean supports(TargetType type) {
        return type != null && supportedTargetTypes.contains(type);
    }

    /** 是否可被 Planner 自主选择：已接入且只读。 */
    public boolean selectable() {
        return available && risk == CapabilityRisk.READ_ONLY;
    }
}