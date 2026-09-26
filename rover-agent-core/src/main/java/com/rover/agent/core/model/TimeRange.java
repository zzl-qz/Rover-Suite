package com.rover.agent.core.model;

/**
 * 调查时间范围，闭区间，单位毫秒。
 *
 * 用户没有指定时间范围时用 {@link #unspecified()}：不表示「零长度窗口」，而是表示
 * 「按各只读数据源的默认窗口取数」，因此调用方必须先判断 {@link #isUnspecified()} 再决定口径。
 *
 * @param fromMillis 起始时刻（含）
 * @param toMillis   结束时刻（含）
 */
public record TimeRange(long fromMillis, long toMillis) {

    private static final TimeRange UNSPECIFIED = new TimeRange(0L, 0L);

    /** 未指定时间范围：交由调查按默认窗口取数。 */
    public static TimeRange unspecified() {
        return UNSPECIFIED;
    }

    /** 是否为未指定时间范围。 */
    public boolean isUnspecified() {
        return fromMillis == 0L && toMillis == 0L;
    }
}