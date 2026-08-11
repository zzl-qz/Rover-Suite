package com.rover.nameserver.client.connection;

import lombok.Builder;
import lombok.Getter;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:55:00
 * Description: Nameserver 客户端参数
 *
 * 这个类是什么：NameserverClient 的配置对象，集中承载连接地址、超时、
 * 心跳/重连开关与周期、在途请求上限等可配置项。
 * 核心职责：通过 Lombok @Builder 供外部链式构造；所有字段有默认值，仅 host/port 必填。
 * 被谁用：NameserverClient 构造时传入；调用方按需覆盖默认值。
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
