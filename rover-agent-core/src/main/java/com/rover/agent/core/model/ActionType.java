package com.rover.agent.core.model;

/** Agent 可提议且已实现确定性执行器的变更类型，目前支持灰度权重调整。 */
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
