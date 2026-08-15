package com.rover.nameserver.client.connection;

import lombok.Builder;
import lombok.Getter;

/**
 * Author: Daylight
 * Created: 2026-08-06 14:35:00
 * Description: Nameserver 客户端连接参数（地址、超时、心跳/重连开关等）
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
