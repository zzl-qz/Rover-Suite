package com.rover.gateway.bootstrap.config;

import com.rover.common.util.HostPort;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.filter.FilterSettings;
import com.rover.gateway.core.route.RouteConfig;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 15:13:00
 * Description: Gateway 启动配置（端口、发现模式、路由等）
 */
@Data
public class GatewayConfig {

    private static final int DEFAULT_PORT = 80;
    private static final int DEFAULT_MAX_CONTENT_LENGTH_BYTES = 1024 * 1024;
    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 3000;
    private static final int DEFAULT_REQUEST_TIMEOUT_MILLIS = 30000;
    private static final long DEFAULT_RECONCILE_INTERVAL_MS = 30000L;

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

    public DiscoveryType getDiscoveryType() {
        return DiscoveryType.from(gatewayProperties().getDiscovery().getType());
    }

    public String getLoadBalanceStrategyOrDefault() {
        LoadBalanceProperties lb = gatewayProperties().getLoadbalance();
        if (lb == null || lb.getStrategy() == null || lb.getStrategy().isBlank()) {
            return "round_robin";
        }
        return lb.getStrategy().trim();
    }

    public FilterSettings toFilterSettings() {
        FilterProperties filterProperties = gatewayProperties().getFilters();
        FilterSettings settings = new FilterSettings();
        if (filterProperties == null) {
            return settings;
        }
        settings.setEnabled(filterProperties.isEnabled());
        settings.setPluginDir(filterProperties.getPluginDir());
        settings.setClasses(filterProperties.getClasses() == null
                ? List.of()
                : List.copyOf(filterProperties.getClasses()));
        return settings;
    }

    public void validate() {
        GatewayProperties gateway = gatewayProperties();
        validatePort(gateway.getPort());
        validatePositive("server.maxContentLengthBytes", getMaxContentLengthBytesOrDefault());
        validatePositive("proxy.connectTimeoutMillis", getConnectTimeoutMillisOrDefault());
        validatePositive("proxy.requestTimeoutMillis", getRequestTimeoutMillisOrDefault());
        validateFilterSettings(gateway.getFilters());

        DiscoveryType discoveryType = getDiscoveryType();
        if (discoveryType == DiscoveryType.NAMESERVER) {
            validateNameserverAddress(gateway.getDiscovery().getNameserver());
        }

        List<RouteProperties> routes = gateway.getRoutes();
        if (routes == null || routes.isEmpty()) {
            return;
        }

        Set<String> businessPrefixes = new HashSet<>();
        for (RouteProperties route : routes) {
            validateRoute(route, businessPrefixes, discoveryType);
        }
    }

    /** 把配置的路由封装成 RouteConfig 集合。 */
    public List<RouteConfig> toRouteConfigs() {
        List<RouteProperties> routes = gatewayProperties().getRoutes();
        if (routes == null || routes.isEmpty()) {
            return List.of();
        }

        DiscoveryType discoveryType = getDiscoveryType();
        List<RouteConfig> routeConfigs = new ArrayList<>(routes.size());
        for (RouteProperties route : routes) {
            if (route.getBusinessPrefix() == null || route.getBusinessPrefix().isBlank()) {
                continue;
            }
            if (discoveryType == DiscoveryType.STATIC && !hasStaticUpstream(route)) {
                continue;
            }
            if (discoveryType == DiscoveryType.NAMESERVER
                    && (route.getServiceName() == null || route.getServiceName().isBlank())) {
                continue;
            }

            RouteConfig routeConfig = new RouteConfig();
            routeConfig.setId(route.getId());
            routeConfig.setBusinessPrefix(route.getBusinessPrefix());
            routeConfig.setTargetUrl(route.getTargetUrl());
            routeConfig.setTargetUrls(copyTargetUrls(route.getTargetUrls()));
            routeConfig.setServiceName(route.getServiceName());
            routeConfig.setGroup(route.getGroup());
            routeConfig.setStripPrefix(resolveStripPrefix(route));
            routeConfigs.add(routeConfig);
        }
        return routeConfigs;
    }

    public DiscoverySettings toDiscoverySettings() {
        DiscoverySettings settings = new DiscoverySettings();
        DiscoveryType type = getDiscoveryType();
        settings.setType(type);

        NameserverProperties nameserver = gatewayProperties().getDiscovery().getNameserver();
        if (nameserver != null) {
            HostPort address = type == DiscoveryType.NAMESERVER
                    ? HostPort.require(nameserver.getAddress(), "discovery.nameserver.address")
                    : HostPort.parseOrNull(nameserver.getAddress(), "discovery.nameserver.address");
            if (address != null) {
                settings.setNameserverHost(address.host());
                settings.setNameserverPort(address.port());
            }
            long reconcile = nameserver.getReconcileIntervalMs() <= 0
                    ? DEFAULT_RECONCILE_INTERVAL_MS
                    : nameserver.getReconcileIntervalMs();
            settings.setReconcileIntervalMs(reconcile);
        }

        if (type == DiscoveryType.NAMESERVER) {
            settings.setSubscribeServices(collectSubscribeServices());
        }
        return settings;
    }

    private List<DiscoverySettings.ServiceSubscribeSpec> collectSubscribeServices() {
        Map<String, DiscoverySettings.ServiceSubscribeSpec> unique = new LinkedHashMap<>();
        for (RouteConfig route : toRouteConfigs()) {
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

    private GatewayProperties gatewayProperties() {
        if (rover == null) {
            rover = new RoverProperties();
        }
        if (rover.getGateway() == null) {
            rover.setGateway(new GatewayProperties());
        }
        GatewayProperties gateway = rover.getGateway();
        if (gateway.getServer() == null) {
            gateway.setServer(new ServerProperties());
        }
        if (gateway.getProxy() == null) {
            gateway.setProxy(new ProxyProperties());
        }
        if (gateway.getFilters() == null) {
            gateway.setFilters(new FilterProperties());
        }
        if (gateway.getDiscovery() == null) {
            gateway.setDiscovery(new DiscoveryProperties());
        }
        if (gateway.getDiscovery().getNameserver() == null) {
            gateway.getDiscovery().setNameserver(new NameserverProperties());
        }
        return gateway;
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

    private void validateFilterSettings(FilterProperties filters) {
        if (filters == null) {
            return;
        }
        if (filters.getPluginDir() == null || filters.getPluginDir().isBlank()) {
            throw new IllegalStateException("Gateway 配置 filters.pluginDir 不能为空");
        }
    }

    private void validateNameserverAddress(NameserverProperties nameserver) {
        if (nameserver == null || nameserver.getAddress() == null || nameserver.getAddress().isBlank()) {
            throw new IllegalStateException("discovery.type=nameserver 时必须配置 discovery.nameserver.address");
        }
        HostPort.require(nameserver.getAddress(), "discovery.nameserver.address");
    }

    private void validateRoute(RouteProperties route, Set<String> businessPrefixes, DiscoveryType discoveryType) {
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

        if (discoveryType == DiscoveryType.STATIC) {
            if (!hasStaticUpstream(route)) {
                throw new IllegalStateException(
                        "static 模式下需要 targetUrl 或 targetUrls，businessPrefix="
                                + route.getBusinessPrefix());
            }
            if (route.getTargetUrl() != null && !route.getTargetUrl().isBlank()) {
                validateTargetUrl(stripWeightSuffix(route.getTargetUrl()));
            }
            if (route.getTargetUrls() != null) {
                for (String raw : route.getTargetUrls()) {
                    if (raw != null && !raw.isBlank()) {
                        validateTargetUrl(stripWeightSuffix(raw.trim()));
                    }
                }
            }
        } else {
            if (route.getServiceName() == null || route.getServiceName().isBlank()) {
                throw new IllegalStateException("nameserver 模式下 serviceName 不能为空，businessPrefix="
                        + route.getBusinessPrefix());
            }
        }

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
        private FilterProperties filters = new FilterProperties();
        private LoadBalanceProperties loadbalance = new LoadBalanceProperties();
        private RewriteProperties rewrite = new RewriteProperties();
        private DiscoveryProperties discovery = new DiscoveryProperties();
        private List<RouteProperties> routes = new ArrayList<>();
    }

    @Data
    public static class LoadBalanceProperties {
        /** round_robin / random / weighted_round_robin / ip_hash / least_connections / 自定义 */
        private String strategy = "round_robin";
    }

    @Data
    public static class DiscoveryProperties {
        /** static | nameserver */
        private String type = "static";
        private NameserverProperties nameserver = new NameserverProperties();
    }

    @Data
    public static class NameserverProperties {
        private String address = "127.0.0.1:8888";
        private long reconcileIntervalMs = DEFAULT_RECONCILE_INTERVAL_MS;
    }

    @Data
    public static class FilterProperties {
        private boolean enabled = true;
        private String pluginDir = "plugins";
        private List<String> classes = new ArrayList<>();
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
        /** 静态多上游，元素可写 http://host:port|weight */
        private List<String> targetUrls = new ArrayList<>();
        private String serviceName;
        private String group;
        private String stripPrefix;
    }

    private static boolean hasStaticUpstream(RouteProperties route) {
        if (route.getTargetUrl() != null && !route.getTargetUrl().isBlank()) {
            return true;
        }
        if (route.getTargetUrls() != null) {
            for (String raw : route.getTargetUrls()) {
                if (raw != null && !raw.isBlank()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> copyTargetUrls(List<String> source) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> copy = new ArrayList<>();
        for (String raw : source) {
            if (raw != null && !raw.isBlank()) {
                copy.add(raw.trim());
            }
        }
        return copy;
    }

    private static String stripWeightSuffix(String raw) {
        int bar = raw.lastIndexOf('|');
        if (bar > 0 && bar < raw.length() - 1) {
            String maybeWeight = raw.substring(bar + 1).trim();
            if (maybeWeight.chars().allMatch(Character::isDigit)) {
                return raw.substring(0, bar).trim();
            }
        }
        return raw;
    }
}
