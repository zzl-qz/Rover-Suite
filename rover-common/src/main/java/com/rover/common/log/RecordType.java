package com.rover.common.log;

/**
 * 落盘记录的类型。
 * 诊断证据类（CONFIG_CHANGE / ROLLBACK / DEPLOY / INSTANCE_EVENT / AGENT_ACTION / ERROR / MEMORY）
 * 是回答「为什么挂了」的关键，写入时按高优对待、尽量不丢；
 * 遥测类（HEARTBEAT / REQUEST_TRACE / GENERIC）量大，允许 best-effort 丢弃。
 *
 * <p>接入状态（当前谁在生产）：
 * <ul>
 *   <li>已接入：CONFIG_CHANGE（配置写成功）、ROLLBACK（回滚成功）、ERROR（配置写失败）、
 *       INSTANCE_EVENT（组件/实例状态翻转）、METRICS_SAMPLE、REQUEST_TRACE（遥测采集器）。</li>
 *   <li>预留（暂无生产者）：DEPLOY（部署流程）、AGENT_ACTION（Agent 处置执行）、
 *       MEMORY（记忆持久化）、HEARTBEAT（原始心跳，已被指标采样+状态翻转蒸馏）、GENERIC。</li>
 * </ul>
 */
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
