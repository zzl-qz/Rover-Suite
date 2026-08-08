package com.rover.nameserver.core.server;

import com.rover.common.protocol.AckMode;
import lombok.Builder;
import lombok.Getter;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: 服务端运行参数
 */
@Getter
@Builder
public class NameserverServerOptions {

    private final int port;
    private final AckMode writeAckMode;
    private final boolean allowClientAckOverride;
    private final boolean clusterEnabled;
    private final int replicationFactor;
    private final String nodeId;
    private final boolean pushEnabled;
    private final long heartbeatTimeoutMillis;
    private final long healthCheckIntervalMillis;
}
