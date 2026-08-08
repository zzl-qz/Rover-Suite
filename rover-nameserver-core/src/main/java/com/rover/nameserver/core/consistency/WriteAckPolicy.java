package com.rover.nameserver.core.consistency;

import com.rover.common.protocol.AckMode;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:40:00
 * Description: 写确认策略，后面集群可以换实现
 */
public interface WriteAckPolicy {

    AckMode resolve(AckMode serverDefault, AckMode requestMode, boolean allowClientOverride);

    int requiredAcks(AckMode mode, int replicationFactor, boolean clusterEnabled);
}
