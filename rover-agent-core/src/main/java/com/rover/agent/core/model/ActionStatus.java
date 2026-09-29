package com.rover.agent.core.model;

/**
 * 一条变更的状态机。
 *
 * <pre>
 *             ┌── 人拒绝 ────────────────► REJECTED
 * PENDING_APPROVAL
 *             └── 人批准 ──► EXECUTING ──► VERIFYING ──► SUCCESS ──► ROLLING_BACK ──► ROLLED_BACK
 *                              │            │
 *                              │            └─► FAILED（提交了但回读不一致）
 *                              ├─► PRECONDITION_FAILED（预检没过，Gateway 一个字节都没动）
 *                              └─► UNCERTAIN（提交了，但连回查都拿不到结果）
 * </pre>
 *
 * <p>状态刻意分成「没提交」与「提交了但不知道结果」两类：前者可以安全地改参数重来，
 * 后者只能拿同一个 operationId 去回查，绝不能重新执行一次——这是幂等与「重试」的分界线。
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
    ROLLED_BACK("已回滚");

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
                || this == ROLLED_BACK;
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
