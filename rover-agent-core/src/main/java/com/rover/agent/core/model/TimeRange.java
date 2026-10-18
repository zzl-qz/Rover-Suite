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

    public TimeRange {
        if (fromMillis < 0L || toMillis < fromMillis) {
            throw new IllegalArgumentException("调查时间范围必须非负，且 fromMillis 不能晚于 toMillis");
        }
    }

    /** 未指定时间范围：交由调查按默认窗口取数。 */
    public static TimeRange unspecified() {
        return UNSPECIFIED;
    }

    /** 是否为未指定时间范围。 */
    public boolean isUnspecified() {
        return fromMillis == 0L && toMillis == 0L;
    }

    public boolean contains(long timestampMillis) {
        return !isUnspecified() && timestampMillis >= fromMillis && timestampMillis <= toMillis;
    }

    public String describe() {
        return isUnspecified() ? "数据源默认窗口"
                : java.time.Instant.ofEpochMilli(fromMillis) + " 至 " + java.time.Instant.ofEpochMilli(toMillis);
    }

    public int windowSeconds() {
        return (int) Math.min(Integer.MAX_VALUE, (toMillis - fromMillis) / 1000L);
    }
}
