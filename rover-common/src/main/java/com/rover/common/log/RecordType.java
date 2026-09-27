package com.rover.common.log;

/**
 * 落盘记录的类型。
 * 诊断证据类（CONFIG_CHANGE / ROLLBACK / DEPLOY / INSTANCE_EVENT / AGENT_ACTION / ERROR / MEMORY）
 * 是回答「为什么挂了」的关键，写入时按高优对待、尽量不丢；
 * 遥测类（HEARTBEAT / REQUEST_TRACE / GENERIC）量大，允许 best-effort 丢弃。
 * MEMORY 预留给后续记忆持久化。
 */
public enum RecordType {
    HEARTBEAT(false),
    REQUEST_TRACE(false),
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
