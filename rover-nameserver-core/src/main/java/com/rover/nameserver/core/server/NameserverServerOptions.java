package com.rover.nameserver.core.server;

import com.rover.common.constants.NameserverConstants;
import com.rover.common.protocol.AckMode;
import lombok.Builder;
import lombok.Getter;

/**
 * Author: Daylight
 * Created: 2026-08-04 16:05:00
 * Description: 服务端运行参数不可变 POJO，由 Builder 装配并集中承载全部启动参数
 */
@Getter
@Builder
public class NameserverServerOptions {

    /** TCP 注册发现端口 */
    private final int port;
    /** HTTP 管理口端口，0 表示关闭 */
    @Builder.Default
    private final int managePort = NameserverConstants.DEFAULT_MANAGE_PORT;
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
    /** 临时实例过期时间 */
    @Builder.Default
    private final long instanceExpireMillis = 30000L;
}