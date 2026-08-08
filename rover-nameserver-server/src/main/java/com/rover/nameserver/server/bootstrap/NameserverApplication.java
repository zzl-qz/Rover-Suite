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
 */
@Slf4j
public class NameserverApplication {

    public static void main(String[] args) {
        NameserverConfig config = new NameserverConfigLoader().load();
        NameserverConfig.NameserverProperties props = config.getRover().getNameserver();

        NameserverServerOptions options = NameserverServerOptions.builder()
                .port(config.getPortOrDefault())
                .writeAckMode(config.resolveWriteAckMode())
                .allowClientAckOverride(props.isAllowClientAckOverride())
                .clusterEnabled(props.getCluster().isEnabled())
                .replicationFactor(props.getCluster().getReplicationFactor())
                .nodeId(props.getCluster().getNodeId())
                .pushEnabled(props.isPushEnabled())
                .heartbeatTimeoutMillis(props.getHeartbeatTimeoutMillis())
                .healthCheckIntervalMillis(props.getHealthCheckIntervalMillis())
                .build();

        NameserverTcpServer server = new NameserverTcpServer(options);
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "nameserver-shutdown"));

        log.info("Rover Nameserver starting on port {}...", options.getPort());
        server.start();
    }
}
