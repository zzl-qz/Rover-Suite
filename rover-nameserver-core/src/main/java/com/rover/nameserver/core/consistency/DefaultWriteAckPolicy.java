package com.rover.nameserver.core.consistency;

import com.rover.common.protocol.AckMode;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:40:00
 * Description: 默认写确认策略
 */
public class DefaultWriteAckPolicy implements WriteAckPolicy {

    @Override
    public AckMode resolve(AckMode serverDefault, AckMode requestMode, boolean allowClientOverride) {
        AckMode fallback = serverDefault == null ? AckMode.IMMEDIATE : serverDefault;
        if (!allowClientOverride || requestMode == null) {
            return fallback;
        }
        return requestMode;
    }

    @Override
    public int requiredAcks(AckMode mode, int replicationFactor, boolean clusterEnabled) {
        int factor = Math.max(replicationFactor, 1);
        // 单机没什么好等的，1 就够了
        if (!clusterEnabled || factor <= 1) {
            return 1;
        }
        AckMode effective = mode == null ? AckMode.IMMEDIATE : mode;
        return switch (effective) {
            case IMMEDIATE -> 1;
            case MAJORITY -> (factor / 2) + 1;
            case ALL -> factor;
        };
    }
}
