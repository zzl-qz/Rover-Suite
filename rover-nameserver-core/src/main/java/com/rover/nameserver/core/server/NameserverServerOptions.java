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
    /** TCP 监听地址，默认 0.0.0.0（不限制部署形态，用户可收紧到本机/内网） */
    @Builder.Default
    private final String bindHost = NameserverConstants.DEFAULT_BIND_HOST;
    /** HTTP 管理口端口，0 表示关闭 */
    @Builder.Default
    private final int managePort = NameserverConstants.DEFAULT_MANAGE_PORT;
    /** HTTP 管理口监听地址，默认 0.0.0.0 */
    @Builder.Default
    private final String manageBindHost = NameserverConstants.DEFAULT_BIND_HOST;
    /** 集群协议鉴权 token（所有请求校验）；空表示不鉴权 */
    private final String token;
    /** HTTP 管理口鉴权 token（X-Rover-Admin-Token 校验）；空表示不鉴权 */
    private final String adminToken;
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
    @Builder.Default
    private final long heartbeatTimeoutMillis = NameserverConstants.DEFAULT_HEARTBEAT_TIMEOUT_MILLIS;
    /** 健康检查间隔 */
    @Builder.Default
    private final long healthCheckIntervalMillis = NameserverConstants.DEFAULT_HEALTH_CHECK_INTERVAL_MILLIS;
    /** 临时实例过期时间 */
    @Builder.Default
    private final long instanceExpireMillis = NameserverConstants.DEFAULT_INSTANCE_EXPIRE_MILLIS;
}
