package com.rover.agent.core.port;

/**
 * Gateway 对一次写操作的记账结果。
 *
 * <p>四种终态互斥且穷尽：要么生效，要么三种「没生效」之一。另有一个 {@link #UNKNOWN} 表示
 * 「网关没有这条操作的记录」——它<b>不等于失败</b>：可能是从未提交过，也可能是操作记录已超出网关的
 * 保留窗口。调用方必须把 UNKNOWN 当成「结果仍未确认」处理，而不是当成成功或失败。
 */
public enum RouteOperationStatus {

    /** 已生效并落盘。 */
    APPLIED,

    /** 幂等重放：这次请求没有再次生效，返回的是之前那一次的结果。 */
    REPLAYED,

    /** 乐观锁冲突，运行态与磁盘都没动。 */
    CONFLICT,

    /** 校验不通过，运行态与磁盘都没动。 */
    REJECTED,

    /** 落盘失败，运行态与磁盘都没动。 */
    FAILED,

    /** 网关没有该操作的记录：可能从未提交，也可能已超出保留窗口。 */
    UNKNOWN;

    /** 解析网关返回的状态字符串；无法识别时按 {@link #UNKNOWN} 处理（宁可说「不确定」）。 */
    public static RouteOperationStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }

    /** 是否确认「这一次操作确实改变了 Gateway」。 */
    public boolean applied() {
        return this == APPLIED || this == REPLAYED;
    }
}
