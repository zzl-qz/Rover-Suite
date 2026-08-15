package com.rover.nameserver.core.consistency;

import com.rover.common.protocol.AckMode;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:40:00
 * Description: 默认写确认策略
 */
public class DefaultWriteAckPolicy implements WriteAckPolicy {

    /**
     * 协商 ACK 模式：默认以服务端配置为准，覆盖需同时满足
     * 「允许客户端覆盖」且「客户端显式指定」两个条件。
     *
     * @return 最终 ACK 模式；serverDefault 为 null 时回退为 {@link AckMode#SINGLE}（非 null）
     */
    @Override
    public AckMode resolve(AckMode serverDefault, AckMode requestMode, boolean allowClientOverride) {
        AckMode fallback = serverDefault == null ? AckMode.SINGLE : serverDefault;
        // 不允许覆盖或客户端未指定：一律用服务端默认
        if (!allowClientOverride || requestMode == null) {
            return fallback;
        }
        return requestMode;
    }

    /** 计算所需确认数：单机恒为 1；集群下依模式为 1 / 过半 / 全副本。 */
    @Override
    public int requiredAcks(AckMode mode, int replicationFactor, boolean clusterEnabled) {
        int factor = Math.max(replicationFactor, 1);
        // 单机部署无副本可等，固定 1 个确认即可
        if (!clusterEnabled || factor == 1) {
            return 1;
        }
        AckMode effective = mode == null ? AckMode.SINGLE : mode;
        return switch (effective) {
            case SINGLE -> 1;
            case HALF -> (factor / 2) + 1;
            case ALL -> factor;
        };
    }
}