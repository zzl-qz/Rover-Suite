package com.rover.nameserver.core.consistency;

import com.rover.common.protocol.AckMode;

/**
 * Author: Daylight
 * Created: 2026-08-03 14:30:00
 * Description: 写确认策略接口，协商最终 ACK 模式并按副本数计算所需确认数，集群化时可替换实现
 */
public interface WriteAckPolicy {

    /** 协商本次写请求最终使用的 ACK 模式；serverDefault 为 null 时视为 {@link AckMode#SINGLE}。 */
    AckMode resolve(AckMode serverDefault, AckMode requestMode, boolean allowClientOverride);

    /** 按 ACK 模式、副本数与集群开关计算需要的确认数（单机恒为 1）。 */
    int requiredAcks(AckMode mode, int replicationFactor, boolean clusterEnabled);
}