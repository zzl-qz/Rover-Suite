package com.rover.nameserver.core.server;

import com.rover.common.protocol.AckMode;
import lombok.Builder;
import lombok.Getter;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: 服务端运行参数
 *
 * 核心职责：以不可变 POJO 集中承载 Nameserver 服务端的全部启动参数，
 * 由 {@link NameserverTcpServer} 在构造时消费，并通过 Builder 模式
 * 供上层应用（如 NameserverApplication 从 YAML）灵活装配。</p>
 *
 * 被谁用：{@link NameserverTcpServer}（端口、超时、开关）、
 * {@link NameserverRequestDispatcher}（ACK 协商、节点标注、副本/集群参数）。
 * 与 {@link com.rover.nameserver.server.bootstrap.config.NameserverConfig}
 * 是「配置源 → 运行参数」的上下游关系。</p>
 */
@Getter
@Builder
public class NameserverServerOptions {

    /** TCP 注册发现端口 */
    private final int port;
    /** HTTP 管理口端口，0 表示关闭 */
    @Builder.Default
    private final int managePort = 8889;
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