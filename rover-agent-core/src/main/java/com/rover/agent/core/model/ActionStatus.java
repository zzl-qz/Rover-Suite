package com.rover.agent.core.model;

/**
 * 变更审批、执行、验证与回滚状态。
 * UNCERTAIN 表示提交结果未知，只能按原 operationId 确认。
 */
public enum ActionStatus {

    /** 仅登记了建议，等待人工批准；Gateway 未被修改，也不会被修改。 */
    PENDING_APPROVAL("待审批"),

    /** 人已批准，正在做状态预检与提交。 */
    EXECUTING("执行中"),

    /** 变更已提交，正在回读路由确认结果。 */
    VERIFYING("验证中"),

    /** 提交成功且回读一致。 */
    SUCCESS("已完成"),

    /** 明确失败：参数被网关拒绝、落盘失败，或回读结果与目标不一致。 */
    FAILED("失败"),

    /** 预检未通过（revision 过期、目标不存在、权重已被改）：没有提交任何写请求。 */
    PRECONDITION_FAILED("预检未通过"),

    /** 结果未知：写请求已发出但没拿到响应，且用原 operationId 也回查不到；需要人工确认。 */
    UNCERTAIN("结果未知"),

    /** 人拒绝了这个变更：未执行，也不会执行。 */
    REJECTED("已拒绝"),

    /** 正在用同一个窄原语把权重补偿回变更前的值。 */
    ROLLING_BACK("回滚中"),

    /** 补偿完成且回读一致。 */
    ROLLED_BACK("已回滚"),

    /**
     * 回滚预检没过：当前权重已经不是这次变更写进去的值，补偿请求没有提交。
     * 不能自动改回去，否则会盖掉别人后来的修改。
     */
    ROLLBACK_PRECONDITION_FAILED("回滚预检未通过");

    private final String label;

    ActionStatus(String label) {
        this.label = label;
    }

    /** 展示用中文名。 */
    public String label() {
        return label;
    }

    /** 是否已经落定、不会再自行变化（{@link #UNCERTAIN} 不算：它可以被回查结果改写）。 */
    public boolean settled() {
        return this == SUCCESS || this == FAILED || this == PRECONDITION_FAILED || this == REJECTED
                || this == ROLLED_BACK || this == ROLLBACK_PRECONDITION_FAILED;
    }

    /** 是否允许人工批准/拒绝。 */
    public boolean awaitingApproval() {
        return this == PENDING_APPROVAL;
    }

    /** 是否可以发起补偿回滚：只有确认生效过的变更才谈得上补偿。 */
    public boolean reversible() {
        return this == SUCCESS;
    }

    /** 是否可以（用原 operationId）回查真实结果。 */
    public boolean resolvable() {
        return this == UNCERTAIN;
    }

    /** 是否已经产生过对 Gateway 的写请求（用于判断「这条变更有没有可能已生效」）。 */
    public boolean submitted() {
        return this == VERIFYING || this == SUCCESS || this == UNCERTAIN || this == ROLLING_BACK
                || this == ROLLED_BACK;
    }
}
