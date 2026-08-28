package com.rover.gateway.bootstrap;

import com.rover.gateway.bootstrap.config.GatewayConfig;
import com.rover.gateway.bootstrap.config.GatewayConfigLoader;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteOverlayStore;
import com.rover.gateway.core.server.GatewayHttpServer;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 加载配置并启动 Gateway
 */
@Slf4j
public class GatewayApplication {

    public static void main(String[] args) {
        log.info("Rover Gateway starting...");

        // 加载gateway配置文件
        GatewayConfig config = new GatewayConfigLoader().load();
        List<RouteConfig> routes = config.toRouteConfigs();

        // Admin 关闭时只认 YAML，避免历史管理面覆盖干扰部署配置。
        if (config.isAdminEnabled()) {
            RouteOverlayStore overlayStore = new RouteOverlayStore();
            if (overlayStore.exists()) {
                routes = overlayStore.loadOrEmpty();
                log.info("使用路由覆盖文件: path={}, routeCount={}",
                        overlayStore.getPath().toAbsolutePath(), routes.size());
            } else {
                log.info("使用 YAML 路由, routeCount={}", routes.size());
            }
        } else {
            log.info("Admin 管理面已关闭，使用 YAML 路由, routeCount={}", routes.size());
        }

        // 服务发现配置
        DiscoverySettings discoverySettings = config.toDiscoverySettings();
        if (discoverySettings.getType().usesServiceDiscovery()) {
            // overlay 之后的最终路由才订阅，Mapper 不再提前扫一遍 YAML
            discoverySettings.setSubscribeServices(DiscoverySettings.subscribeSpecsFrom(routes));
        }
        log.info("discovery.type={}, routeCount={}", discoverySettings.getType(), routes.size());

        // 出站实现：YAML 写到系统属性，HttpProxyClient 构造时读取。jdk 那条是第一版，别删。
        System.setProperty(
                com.rover.gateway.core.config.GatewaySystemProperties.PROXY_OUTBOUND,
                config.getProxyOutboundOrDefault());
        System.setProperty(
                com.rover.gateway.core.config.GatewaySystemProperties.IO_TRANSPORT,
                config.getIoTransportOrDefault());
        log.info("proxy.outbound={}", config.getProxyOutboundOrDefault());

        // 封装启动服务，内置接收前端HTTP请求+定时拉取NameSever实例信息的功能
        GatewayHttpServer server = new GatewayHttpServer(
                config.getPortOrDefault(),
                routes,
                config.getMaxContentLengthBytesOrDefault(),
                config.getConnectTimeoutMillisOrDefault(),
                config.getRequestTimeoutMillisOrDefault(),
                config.toFilterSettings(),
                discoverySettings,
                config.getLoadBalanceStrategyOrDefault(),
                config.toCorsSettings(),
                config.getBindHostOrDefault(),
                config.getAdminTokenOrDefault(),
                config.isAdminEnabled(),
                config.isMetricsEnabled(),
                config.getMetricsWindowSecondsOrDefault(),
                config.isTraceEnabled(),
                config.getTraceSlowThresholdMillisOrDefault(),
                config.getTraceSampleRateOrDefault(),
                config.isDispatchOnEventLoop());

        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "gateway-shutdown"));
        server.start();
    }
}
