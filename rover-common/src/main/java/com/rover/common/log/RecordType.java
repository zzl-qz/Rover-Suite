package com.rover.common.log;

/** 落盘记录类型；诊断证据使用高优队列，遥测允许队列满时丢弃。 */
public enum RecordType {
    HEARTBEAT(false),
    REQUEST_TRACE(false),
    METRICS_SAMPLE(false),
    CONFIG_CHANGE(true),
    ROLLBACK(true),
    DEPLOY(true),
    INSTANCE_EVENT(true),
    AGENT_ACTION(true),
    ERROR(true),
    MEMORY(true),
    GENERIC(false);

    private final boolean critical;

    RecordType(boolean critical) {
        this.critical = critical;
    }

    /** 是否为诊断证据：高优、尽量不丢 */
    public boolean isCritical() {
        return critical;
    }
}
