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

    /** Nameserver 地址 */
    private final String host;
    /** Nameserver 端口 */
    private final int port;
    /** 连接超时 */
    @Builder.Default
    private final int connectTimeoutMs = 3000;
    /** 单次请求超时 */
    @Builder.Default
    private final int requestTimeoutMs = 3000;
    /** 心跳间隔 */
    @Builder.Default
    private final long heartbeatIntervalMs = 5000L;
    /** 自动心跳 */
    @Builder.Default
    private final boolean autoHeartbeat = true;
    /** 自动重连 */
    @Builder.Default
    private final boolean autoReconnect = true;
    /** 重连间隔 */
    @Builder.Default
    private final long reconnectIntervalMs = 3000L;
    /** 最大在途请求数 */
    @Builder.Default
    private final int maxPendingRequests = 10000;
}
