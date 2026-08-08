/**
 * 作者：Daylight
 * 创建时间：2026-08-08 15:13:00
 * 描述：描述 Nameserver 启动配置
 */
package com.rover.nameserver.server.bootstrap.config;

import lombok.Data;

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

    @Data
    public static class RoverProperties {

        private NameserverProperties nameserver = new NameserverProperties();
    }

    @Data
    public static class NameserverProperties {

        private int port = DEFAULT_PORT;
        private long heartbeatTimeoutMillis = 15000L;
        private long healthCheckIntervalMillis = 5000L;
        private long instanceExpireMillis = 30000L;
        private boolean pushEnabled = true;
    }
}
