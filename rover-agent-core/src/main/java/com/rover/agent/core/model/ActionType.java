package com.rover.agent.core.model;

/**
 * Agent 可以提议的运维变更类型。
 *
 * <p>这里<b>只列真实具备执行原语的动作</b>：每一种都必须有一个收窄的、可验证的、可补偿的写入接口，
 * 并且已经由确定性执行器实现。不写「将来可能支持」的类型——枚举一旦多出一项，
 * 模型就可能提议一个没人能执行、也没人能拒绝的计划。
 *
 * <p>目前只有灰度权重调整：Gateway 的 {@code POST /_manage/routes/targets/weight} 是单版本权重原语，
 * 范围窄（只改一个目标的权重）、天然幂等（配合 operationId）、天然可回滚（权重改回去就是补偿），
 * 因此它是第一个、也是当前唯一的受控动作。
 */
public enum ActionType {

    /** 调整某条路由下某个版本（service@group）的流量权重。 */
    ADJUST_ROUTE_TARGET_WEIGHT("灰度权重调整");

    private final String label;

    ActionType(String label) {
        this.label = label;
    }

    /** 展示用中文名；前端按类型渲染卡片标题时用它，不自己拼字典。 */
    public String label() {
        return label;
    }
}
