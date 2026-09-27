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
        // loadOrNull 在文件缺失/为空/格式不可解析时返回 null，此时回退 YAML，
        // 而不是把「读不出覆盖文件」当成「路由表是空的」——后者会让网关以零路由启动。
        RouteOverlayStore overlayStore = new RouteOverlayStore();
        RouteOverlayStore.AppliedState applied = config.isAdminEnabled() ? overlayStore.loadOrNull() : null;
        if (applied != null) {
            routes = applied.routes();
            log.info("使用路由覆盖文件: path={}, routeCount={}, revision={}",
                    overlayStore.getPath().toAbsolutePath(), routes.size(), applied.revision());
        } else if (config.isAdminEnabled()) {
            log.info("没有可用的路由覆盖文件，使用 YAML 路由, routeCount={}", routes.size());
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

        // 出站实现
        System.setProperty(
                com.rover.gateway.core.config.GatewaySystemProperties.PROXY_OUTBOUND,
                config.getProxyOutboundOrDefault()); // 指定使用netty还是jdk
        System.setProperty(
                com.rover.gateway.core.config.GatewaySystemProperties.IO_TRANSPORT,
                config.getIoTransportOrDefault()); // 指定EventLoop模式
        config.exportPositiveOverrides();
        log.info("proxy.outbound={}, connectTimeoutMillis={}, requestTimeoutMillis={}, maxConnectionsPerEventLoop={}, maxPendingAcquires={}, maxInflight={}, inboundIdleTimeoutSeconds={}, outboundIdleTimeoutSeconds={}",
                config.getProxyOutboundOrDefault(),
                config.getConnectTimeoutMillisOrDefault(),
                config.getRequestTimeoutMillisOrDefault(),
                config.getMaxConnectionsPerEventLoopOrDefault(),
                config.getMaxPendingAcquiresOrDefault(),
                config.getMaxInflightOrDefault(),
                config.getInboundIdleTimeoutSecondsOrDefault(),
                config.getOutboundIdleTimeoutSecondsOrDefault());

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
        // 只对齐版本号：路由内容已由上一步决定，重启后报出的版本必须和重启前确认过的版本一致，
        // 否则调用方拿着旧版本号重放会被误判成冲突。没有覆盖文件时回到 0 号版本。
        server.restoreRoutesRevision(
                applied == null ? 0 : applied.revision(),
                applied == null ? "" : applied.appliedOperationId());
        server.start();
    }
}
