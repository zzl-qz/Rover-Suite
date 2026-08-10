package com.rover.nameserver.server.bootstrap.config;

import com.rover.common.protocol.AckMode;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 15:13:00
 * Description: Nameserver 配置
 */
@Data
public class NameserverConfig {

    private static final int DEFAULT_PORT = 8888;

    private RoverProperties rover = new RoverProperties();

    public int getPortOrDefault() {
        int port = rover.getNameserver().getPort();
        if (port <= 0) {
            return DEFAULT_PORT;
        }
        return port;
    }

    public AckMode resolveWriteAckMode() {
        return AckMode.fromName(rover.getNameserver().getWriteAckMode());
    }

    @Data
    public static class RoverProperties {

        private NameserverProperties nameserver = new NameserverProperties();
    }

    @Data
    public static class NameserverProperties {

        private int port = DEFAULT_PORT;
        /** HTTP 管理口，Admin 调这里 */
        private int managePort = 8889;
        private long heartbeatTimeoutMillis = 15000L;
        private long healthCheckIntervalMillis = 5000L;
        private long instanceExpireMillis = 30000L;
        private boolean pushEnabled = true;

        // IMMEDIATE / MAJORITY / ALL，单机先都当本地确认
        private String writeAckMode = AckMode.IMMEDIATE.name();

        // 一般别让客户端自己改 ack 强度
        private boolean allowClientAckOverride = false;

        private ClusterProperties cluster = new ClusterProperties();
    }

    @Data
    public static class ClusterProperties {

        // 先关着，后面真做集群再开
        private boolean enabled = false;
        private String nodeId;
        private List<String> nodes = new ArrayList<>();
        private int replicationFactor = 1;
    }
}
