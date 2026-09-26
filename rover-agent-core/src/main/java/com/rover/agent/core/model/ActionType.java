package com.rover.agent.core.model;

/**
 * 处置动作类型：本阶段只用于生成处置计划（{@link ActionPlan}），不执行任何动作。
 *
 * 动作集合刻意很窄：只覆盖 Rover 管理口将来最有价值、且语义可判定的几类，
 * 其余一律归入 {@link #UNKNOWN}，如实告知「未能识别为本版本支持的处置动作」。
 */
public enum ActionType {

    /** 摘除实例：把实例从负载均衡中摘除，停止接收新流量 */
    DRAIN_INSTANCE("摘除实例"),

    /** 恢复实例：把已摘除的实例重新加入负载均衡 */
    RESTORE_INSTANCE("恢复实例"),

    /** 调整路由超时：修改某条路由的超时时间 */
    UPDATE_ROUTE_TIMEOUT("调整路由超时"),

    /** 调整限流：修改某条路由的限流配置 */
    UPDATE_RATE_LIMIT("调整限流"),

    /** 未能识别为受支持的处置动作 */
    UNKNOWN("未识别的动作");

    private final String label;

    ActionType(String label) {
        this.label = label;
    }

    /** 中文展示名。 */
    public String label() {
        return label;
    }

    /** 是否作用于实例对象。 */
    public boolean targetsInstance() {
        return this == DRAIN_INSTANCE || this == RESTORE_INSTANCE;
    }

    /** 是否作用于路由对象。 */
    public boolean targetsRoute() {
        return this == UPDATE_ROUTE_TIMEOUT || this == UPDATE_RATE_LIMIT;
    }
}