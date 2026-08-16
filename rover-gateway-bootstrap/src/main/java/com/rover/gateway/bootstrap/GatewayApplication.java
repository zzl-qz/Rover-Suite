package com.rover.gateway.bootstrap;

import com.rover.gateway.bootstrap.config.GatewayConfig;
import com.rover.gateway.bootstrap.config.GatewayConfigLoader;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteOverlayStore;
import com.rover.gateway.core.server.GatewayHttpServer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        GatewayConfig config = new GatewayConfigLoader().load();

        List<RouteConfig> routes = config.toRouteConfigs();
        RouteOverlayStore overlayStore = new RouteOverlayStore();
        if (overlayStore.exists()) {
            routes = overlayStore.loadOrEmpty();
            log.info("使用路由覆盖文件: path={}, routeCount={}",
                    overlayStore.getPath().toAbsolutePath(), routes.size());
        } else {
            log.info("使用 YAML 路由, routeCount={}", routes.size());
        }

        // todo 这里后续改成工厂模式，然后支持多种注册中心比较好
        DiscoverySettings discoverySettings = config.toDiscoverySettings();
        if (discoverySettings.getType() == DiscoveryType.NAMESERVER) {
            discoverySettings.setSubscribeServices(subscribeSpecsFrom(routes));
        }

        log.info("discovery.type={}, routeCount={}", discoverySettings.getType(), routes.size());

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
                config.getAdminTokenOrDefault());

        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "gateway-shutdown"));
        server.start();
    }

    /** 从路由中提取待订阅的 Nameserver 服务（去重）。 */
    private static List<DiscoverySettings.ServiceSubscribeSpec> subscribeSpecsFrom(List<RouteConfig> routes) {
        Map<String, DiscoverySettings.ServiceSubscribeSpec> unique = new LinkedHashMap<>();
        for (RouteConfig route : routes) {
            if (route.getServiceName() == null || route.getServiceName().isBlank()) {
                continue;
            }
            String key = route.getServiceName() + "#" + (route.getGroup() == null ? "" : route.getGroup());
            DiscoverySettings.ServiceSubscribeSpec spec = new DiscoverySettings.ServiceSubscribeSpec();
            spec.setServiceName(route.getServiceName());
            spec.setGroup(route.getGroup());
            unique.putIfAbsent(key, spec);
        }
        return new ArrayList<>(unique.values());
    }
}
