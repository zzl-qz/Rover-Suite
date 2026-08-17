package com.rover.nameserver.core.registry;

/**
 * Author: Daylight
 * Created: 2026-08-17 09:45:00
 * Description: 心跳处理结果：区分「实例不存在 / 仅刷新 / 恢复健康需推送」
 */
public final class HeartbeatResult {

    private final boolean found;
    private final RegistrySnapshot healthRecoveredSnapshot;

    private HeartbeatResult(boolean found, RegistrySnapshot healthRecoveredSnapshot) {
        this.found = found;
        this.healthRecoveredSnapshot = healthRecoveredSnapshot;
    }

    public static HeartbeatResult notFound() {
        return new HeartbeatResult(false, null);
    }

    /** 心跳已刷新，健康状态未变，无需推送。 */
    public static HeartbeatResult touched() {
        return new HeartbeatResult(true, null);
    }

    /** 从不健康恢复为健康，调用方应推送快照。 */
    public static HeartbeatResult recovered(RegistrySnapshot snapshot) {
        return new HeartbeatResult(true, snapshot);
    }

    public boolean isFound() {
        return found;
    }

    /** 非 null 时表示健康状态翻转，需要推送给订阅方。 */
    public RegistrySnapshot healthRecoveredSnapshot() {
        return healthRecoveredSnapshot;
    }
}
