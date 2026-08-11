package com.rover.nameserver.server.bootstrap;

import com.rover.nameserver.core.server.NameserverServerOptions;
import com.rover.nameserver.core.server.NameserverTcpServer;
import com.rover.nameserver.server.bootstrap.config.NameserverConfig;
import com.rover.nameserver.server.bootstrap.config.NameserverConfigLoader;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 启动 Rover nameserver 服务
 *
 * 这个类是什么：Nameserver 进程入口。
 * 核心职责：main 里做启动编排——
 * ①用 {@link NameserverConfigLoader} 加载 rover-nameserver.yml；
 * ②映射成核心模块的 {@link NameserverServerOptions}；
 * ③构造并启动 {@link NameserverTcpServer}（TCP 注册发现 + HTTP 管理口）；
 * ④注册 JVM 关闭钩子，进程退出（含 Ctrl+C）时优雅关闭。
 * 被谁用：运维/部署方用 java 命令直接启动；装配细节在核心模块，本类只管「读配置 → 启动」。
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
                .managePort(props.getManagePort())
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
        // 关闭钩子：JVM 退出（kill/Ctrl+C）时执行优雅关闭，尽量释放端口与线程
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "nameserver-shutdown"));

        log.info("Rover Nameserver starting on port {}, managePort={}...",
                options.getPort(), options.getManagePort());
        // start 为阻塞前置成功后返回；失败会抛出 IllegalStateException 结束进程
        server.start();
    }
}