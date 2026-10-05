package com.rover.agent.core.port;

/** 网关写操作状态；UNKNOWN 表示无操作记录，结果仍未确认。 */
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
