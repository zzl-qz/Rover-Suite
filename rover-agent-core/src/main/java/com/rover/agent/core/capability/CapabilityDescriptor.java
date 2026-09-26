package com.rover.agent.core.capability;

import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.TargetType;
import java.util.List;

/**
 * 能力的注册描述：告诉 Agent「当前 Rover 到底有什么能力」。
 *
 * {@code available} 是诚实的开关：没有数据适配器的能力照样注册（这样模型与用户都能看到
 * 「有哪些能力、哪些还没开放」），但 Planner 不能选择它们——选择与执行的边界都在代码里。
 *
 * {@code supportedTargetTypes} 里的 {@link TargetType#UNKNOWN} 表示该能力不需要具体目标
 * （全局口径，如 Gateway 全局指标）。
 *
 * @param id                  能力标识
 * @param name                展示名
 * @param description         能力说明（人读）
 * @param risk                风险级别；Planner 只能选择 READ_ONLY
 * @param supportedTargetTypes 支持的目标类型
 * @param available           当前是否已接入可用的数据适配器
 * @param stepType            执行该能力时上报的步骤类型
 * @param stepName            执行该能力时上报的步骤名
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