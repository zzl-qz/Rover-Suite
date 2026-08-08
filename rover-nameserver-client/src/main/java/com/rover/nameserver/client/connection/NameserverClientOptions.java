package com.rover.nameserver.client.connection;

import lombok.Builder;
import lombok.Getter;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:55:00
 * Description: Nameserver 客户端参数
 */
@Getter
@Builder
public class NameserverClientOptions {

    private final String host;
    private final int port;

    @Builder.Default
    private final int connectTimeoutMs = 3000;

    @Builder.Default
    private final int requestTimeoutMs = 3000;

    @Builder.Default
    private final long heartbeatIntervalMs = 5000L;

    @Builder.Default
    private final boolean autoHeartbeat = true;

    @Builder.Default
    private final boolean autoReconnect = true;

    @Builder.Default
    private final long reconnectIntervalMs = 3000L;

    @Builder.Default
    private final int maxPendingRequests = 10000;
}
