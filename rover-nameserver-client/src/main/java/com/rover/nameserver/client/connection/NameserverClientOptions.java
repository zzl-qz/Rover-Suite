package com.rover.nameserver.client.connection;

import com.rover.common.concurrent.PendingRequestTable;
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

    /** 默认连接超时（毫秒） */
    static final int DEFAULT_CONNECT_TIMEOUT_MS = 3000;
    /** 默认单次请求超时（毫秒） */
    static final int DEFAULT_REQUEST_TIMEOUT_MS = 3000;
    /** 默认心跳间隔（毫秒） */
    static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 5000L;
    /** 默认重连间隔（毫秒） */
    static final long DEFAULT_RECONNECT_INTERVAL_MS = 3000L;

    /** Nameserver 地址 */
    private final String host;
    /** Nameserver 端口 */
    private final int port;
    /** 连接超时 */
    @Builder.Default
    private final int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    /** 单次请求超时 */
    @Builder.Default
    private final int requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS;
    /** 心跳间隔 */
    @Builder.Default
    private final long heartbeatIntervalMs = DEFAULT_HEARTBEAT_INTERVAL_MS;
    /** 自动心跳 */
    @Builder.Default
    private final boolean autoHeartbeat = true;
    /** 自动重连 */
    @Builder.Default
    private final boolean autoReconnect = true;
    /** 重连间隔 */
    @Builder.Default
    private final long reconnectIntervalMs = DEFAULT_RECONNECT_INTERVAL_MS;
    /** 最大在途请求数 */
    @Builder.Default
    private final int maxPendingRequests = PendingRequestTable.DEFAULT_MAX_PENDING;
    /** 集群鉴权 token，所有协议请求均携带；空表示不鉴权 */
    private final String token;
}
