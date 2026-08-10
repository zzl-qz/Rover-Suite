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

    /** 监听端口 */
    private final int port;
    /** 写确认模式，集群预留 */
    private final AckMode writeAckMode;
    /** 是否允许客户端覆盖 ack */
    private final boolean allowClientAckOverride;
    /** 集群开关，当前基本关闭 */
    private final boolean clusterEnabled;
    /** 副本数，集群预留 */
    private final int replicationFactor;
    /** 本节点 ID */
    private final String nodeId;
    /** 是否推送变更 */
    private final boolean pushEnabled;
    /** 心跳超时 */
    private final long heartbeatTimeoutMillis;
    /** 健康检查间隔 */
    private final long healthCheckIntervalMillis;
}
