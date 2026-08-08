package com.rover.gateway.bootstrap;

import com.rover.gateway.bootstrap.config.GatewayConfig;
import com.rover.gateway.bootstrap.config.GatewayConfigLoader;
import com.rover.gateway.core.server.GatewayHttpServer;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 加载 Gateway 配置、组装过滤器链并启动 HTTP 服务
 */
@Slf4j
public class GatewayApplication {

    public static void main(String[] args) {
        log.info("Rover Gateway starting...");
        GatewayConfig config = new GatewayConfigLoader().load();
        log.info("config is {}", config);
        GatewayHttpServer server = new GatewayHttpServer(
                config.getPortOrDefault(),
                config.toRouteConfigs(),
                config.getMaxContentLengthBytesOrDefault(),
                config.getConnectTimeoutMillisOrDefault(),
                config.getRequestTimeoutMillisOrDefault(),
                config.toFilterSettings());
        server.start();
    }
}
