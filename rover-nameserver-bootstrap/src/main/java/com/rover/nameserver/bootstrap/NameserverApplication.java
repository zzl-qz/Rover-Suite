package com.rover.nameserver.bootstrap;

import com.rover.nameserver.core.server.NameserverServerOptions;
import com.rover.nameserver.core.server.NameserverTcpServer;
import com.rover.nameserver.bootstrap.config.*;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 启动 Rover nameserver 服务
 */
@Slf4j
public class NameserverApplication {

    /**
     * 启动入口。
     *
     * @param args 命令行参数（当前未使用，预留）
     */
    public static void main(String[] args) {
        // 加载配置：外部 config/rover-nameserver.yml 优先，classpath 兜底，再默认
        NameserverConfig config = new NameserverConfigLoader().load();
        NameserverConfig.NameserverProperties props = config.getRover().getNameserver();

        // 配置 → 运行参数映射，含端口默认值、ACK 模式解析等派生逻辑
        NameserverServerOptions options = NameserverServerOptions.builder()
                .port(config.getPortOrDefault())
                .bindHost(props.getBindHost())
                .managePort(props.getManagePort())
                .manageBindHost(props.getManageBindHost())
                .token(props.getToken())
                .adminToken(props.getAdminToken())
                .clientApiEnabled(props.isClientApiEnabled())
                .writeAckMode(config.resolveWriteAckMode())
                .allowClientAckOverride(props.isAllowClientAckOverride())
                .clusterEnabled(props.getCluster().isEnabled())
                .replicationFactor(props.getCluster().getReplicationFactor())
                .nodeId(props.getCluster().getNodeId())
                .pushEnabled(props.isPushEnabled())
                .heartbeatTimeoutMillis(props.getHeartbeatTimeoutMillis())
                .healthCheckIntervalMillis(props.getHealthCheckIntervalMillis())
                .instanceExpireMillis(props.getInstanceExpireMillis())
                .build();

        NameserverTcpServer server = new NameserverTcpServer(options);
        // 优雅关闭
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "nameserver-shutdown"));

        log.info("Rover Nameserver starting on port {}, managePort={}...",
                options.getPort(), options.getManagePort());
        // start 为阻塞前置成功后返回；失败会抛出 IllegalStateException 结束进程
        server.start();
    }
}
