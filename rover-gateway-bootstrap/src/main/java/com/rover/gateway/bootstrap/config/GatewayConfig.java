/**
 * 作者：Daylight
 * 创建时间：2026-08-08 15:13:00
 * 描述：描述 Gateway 启动配置
 */
package com.rover.gateway.bootstrap.config;

import com.rover.gateway.core.route.RouteConfig;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Data;

@Data
public class GatewayConfig {

    private static final int DEFAULT_PORT = 80;
    private static final int DEFAULT_MAX_CONTENT_LENGTH_BYTES = 1024 * 1024;
    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 3000;
    private static final int DEFAULT_REQUEST_TIMEOUT_MILLIS = 30000;

    private RoverProperties rover = new RoverProperties();

    public int getPortOrDefault() {
        return gatewayProperties().getPort();
    }

    public int getMaxContentLengthBytesOrDefault() {
        return gatewayProperties().getServer().getMaxContentLengthBytes();
    }

    public int getConnectTimeoutMillisOrDefault() {
        return gatewayProperties().getProxy().getConnectTimeoutMillis();
    }

    public int getRequestTimeoutMillisOrDefault() {
        return gatewayProperties().getProxy().getRequestTimeoutMillis();
    }

    /**
     * 校验用户配置，尽量在 Gateway 启动阶段暴露配置错误。
     */
    public void validate() {
        GatewayProperties gateway = gatewayProperties();
        validatePort(gateway.getPort());
        validatePositive("server.maxContentLengthBytes", getMaxContentLengthBytesOrDefault());
        validatePositive("proxy.connectTimeoutMillis", getConnectTimeoutMillisOrDefault());
        validatePositive("proxy.requestTimeoutMillis", getRequestTimeoutMillisOrDefault());

        List<RouteProperties> routes = gateway.getRoutes();
        if (routes == null || routes.isEmpty()) {
            return;
        }

        Set<String> businessPrefixes = new HashSet<>();
        for (RouteProperties route : routes) {
            validateRoute(route, businessPrefixes);
        }
    }

    /**
     * 将 YAML 路由配置转换为 Gateway 运行时路由对象。
     */
    public List<RouteConfig> toRouteConfigs() {
        List<RouteProperties> routes = gatewayProperties().getRoutes();
        if (routes == null || routes.isEmpty()) {
            return List.of();
        }

        List<RouteConfig> routeConfigs = new ArrayList<>(routes.size());
        for (RouteProperties route : routes) {
            if (route.getBusinessPrefix() == null || route.getBusinessPrefix().isBlank()
                    || route.getTargetUrl() == null || route.getTargetUrl().isBlank()) {
                continue;
            }

            RouteConfig routeConfig = new RouteConfig();
            routeConfig.setId(route.getId());
            routeConfig.setBusinessPrefix(route.getBusinessPrefix());
            routeConfig.setTargetUrl(route.getTargetUrl());
            routeConfig.setStripPrefix(resolveStripPrefix(route));
            routeConfigs.add(routeConfig);
        }
        return routeConfigs;
    }

    private GatewayProperties gatewayProperties() {
        if (rover == null) {
            rover = new RoverProperties();
        }
        if (rover.getGateway() == null) {
            rover.setGateway(new GatewayProperties());
        }
        if (rover.getGateway().getServer() == null) {
            rover.getGateway().setServer(new ServerProperties());
        }
        if (rover.getGateway().getProxy() == null) {
            rover.getGateway().setProxy(new ProxyProperties());
        }
        return rover.getGateway();
    }

    private String resolveStripPrefix(RouteProperties route) {
        if (route.getStripPrefix() != null) {
            return route.getStripPrefix();
        }
        if (gatewayProperties().getRewrite() == null) {
            return null;
        }
        return gatewayProperties().getRewrite().getStripPrefix();
    }

    private void validatePort(int port) {
        if (port <= 0 || port > 65535) {
            throw new IllegalStateException("Gateway 端口配置非法：" + port);
        }
    }

    private void validatePositive(String configName, int value) {
        if (value <= 0) {
            throw new IllegalStateException("Gateway 配置必须大于 0：" + configName + "=" + value);
        }
    }

    private void validateRoute(RouteProperties route, Set<String> businessPrefixes) {
        if (route.getBusinessPrefix() == null || route.getBusinessPrefix().isBlank()) {
            throw new IllegalStateException("Gateway 路由 businessPrefix 不能为空，routeId=" + route.getId());
        }
        if (!route.getBusinessPrefix().startsWith("/")) {
            throw new IllegalStateException("Gateway 路由 businessPrefix 必须以 / 开头："
                    + route.getBusinessPrefix());
        }
        if (!businessPrefixes.add(route.getBusinessPrefix())) {
            throw new IllegalStateException("Gateway 路由 businessPrefix 重复："
                    + route.getBusinessPrefix());
        }
        if (route.getTargetUrl() == null || route.getTargetUrl().isBlank()) {
            throw new IllegalStateException("Gateway 路由 targetUrl 不能为空，businessPrefix="
                    + route.getBusinessPrefix());
        }

        validateTargetUrl(route.getTargetUrl());
        validateStripPrefix(resolveStripPrefix(route), route.getBusinessPrefix());
    }

    private void validateTargetUrl(String targetUrl) {
        try {
            URI uri = new URI(targetUrl);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalStateException("Gateway 路由 targetUrl 只支持 http/https：" + targetUrl);
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalStateException("Gateway 路由 targetUrl 必须包含主机地址：" + targetUrl);
            }
        } catch (URISyntaxException err) {
            throw new IllegalStateException("Gateway 路由 targetUrl 格式非法：" + targetUrl, err);
        }
    }

    private void validateStripPrefix(String stripPrefix, String businessPrefix) {
        if (stripPrefix == null || stripPrefix.isBlank()) {
            return;
        }
        if (!stripPrefix.startsWith("/")) {
            throw new IllegalStateException("Gateway 路由 stripPrefix 必须以 / 开头：" + stripPrefix);
        }
        if (!businessPrefix.equals(stripPrefix) && !businessPrefix.startsWith(stripPrefix + "/")) {
            throw new IllegalStateException("Gateway 路由 stripPrefix 必须是 businessPrefix 的前缀："
                    + stripPrefix + " -> " + businessPrefix);
        }
    }

    @Data
    public static class RoverProperties {

        private GatewayProperties gateway = new GatewayProperties();
    }

    @Data
    public static class GatewayProperties {

        private int port = DEFAULT_PORT;
        private ServerProperties server = new ServerProperties();
        private ProxyProperties proxy = new ProxyProperties();
        private RewriteProperties rewrite = new RewriteProperties();
        private List<RouteProperties> routes = new ArrayList<>();
    }

    @Data
    public static class ServerProperties {

        private int maxContentLengthBytes = DEFAULT_MAX_CONTENT_LENGTH_BYTES;
    }

    @Data
    public static class ProxyProperties {

        private int connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS;
        private int requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MILLIS;
    }

    @Data
    public static class RewriteProperties {

        private String stripPrefix;
    }

    @Data
    public static class RouteProperties {

        private String id;
        private String businessPrefix;
        private String targetUrl;
        private String stripPrefix;
    }
}
