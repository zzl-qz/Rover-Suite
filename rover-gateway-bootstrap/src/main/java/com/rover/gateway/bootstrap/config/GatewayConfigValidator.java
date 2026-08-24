package com.rover.gateway.bootstrap.config;

import com.rover.common.constants.HttpConstants;
import com.rover.common.util.HostPort;
import com.rover.gateway.bootstrap.config.GatewayConfig.FilterProperties;
import com.rover.gateway.bootstrap.config.GatewayConfig.GatewayProperties;
import com.rover.gateway.bootstrap.config.GatewayConfig.NameserverProperties;
import com.rover.gateway.bootstrap.config.GatewayConfig.RateLimitProperties;
import com.rover.gateway.bootstrap.config.GatewayConfig.RouteProperties;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.filter.ratelimit.RateLimitSettings;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Gateway YAML 校验：端口、超时、过滤/限流、发现地址、路由前缀与上游。
 * GatewayConfig 仍暴露 validate()，逻辑集中在这里。
 */
final class GatewayConfigValidator {

    private static final String NAMESERVER_ADDRESS_CONFIG = "discovery.nameserver.address";

    private GatewayConfigValidator() {
    }

    static void validate(GatewayConfig config) {
        GatewayProperties gateway = config.gatewayProperties();
        validatePort(gateway.getPort());
        validatePositive("server.maxContentLengthBytes", config.getMaxContentLengthBytesOrDefault());
        validatePositive("proxy.connectTimeoutMillis", config.getConnectTimeoutMillisOrDefault());
        validatePositive("proxy.requestTimeoutMillis", config.getRequestTimeoutMillisOrDefault());
        validateFilterSettings(gateway.getFilters());
        validateRateLimit(gateway.getRateLimit());

        DiscoveryType discoveryType = config.getDiscoveryType();
        if (discoveryType == DiscoveryType.NAMESERVER) {
            validateNameserverAddress(gateway.getDiscovery().getNameserver());
        }

        List<RouteProperties> routes = gateway.getRoutes();
        if (routes == null || routes.isEmpty()) {
            return;
        }

        Set<String> businessPrefixes = new HashSet<>();
        for (RouteProperties route : routes) {
            validateRoute(config, route, businessPrefixes, discoveryType);
        }
    }

    private static void validatePort(int port) {
        if (port <= 0 || port > 65535) {
            throw new IllegalStateException("Gateway 端口配置非法：" + port);
        }
    }

    private static void validatePositive(String configName, int value) {
        if (value <= 0) {
            throw new IllegalStateException("Gateway 配置必须大于 0：" + configName + "=" + value);
        }
    }

    private static void validateFilterSettings(FilterProperties filters) {
        if (filters == null) {
            return;
        }
        if (filters.getPluginDir() == null || filters.getPluginDir().isBlank()) {
            throw new IllegalStateException("Gateway 配置 filters.pluginDir 不能为空");
        }
    }

    private static void validateRateLimit(RateLimitProperties rate) {
        if (rate == null) {
            return;
        }
        String algorithm = rate.getAlgorithm() == null
                ? RateLimitSettings.TOKEN_BUCKET
                : rate.getAlgorithm().trim().toLowerCase(Locale.ROOT);
        if (!RateLimitSettings.TOKEN_BUCKET.equals(algorithm)
                && !RateLimitSettings.SLIDING_WINDOW.equals(algorithm)) {
            throw new IllegalArgumentException(
                    "rateLimit.algorithm 仅支持 token_bucket/sliding_window");
        }
        String key = rate.getKey() == null
                ? RateLimitSettings.PATH
                : rate.getKey().trim().toLowerCase(Locale.ROOT);
        if (!RateLimitSettings.GLOBAL.equals(key) && !RateLimitSettings.PATH.equals(key)) {
            throw new IllegalArgumentException("rateLimit.key 仅支持 global/path");
        }
        if (rate.getPermitsPerSecond() <= 0 || rate.getBurst() <= 0 || rate.getLimit() <= 0) {
            throw new IllegalArgumentException("rateLimit 限流值必须大于 0");
        }
        if (rate.getWindowSeconds() <= 0 || rate.getWindowSeconds() > 3600) {
            throw new IllegalArgumentException("rateLimit.windowSeconds 必须在 1~3600 之间");
        }
    }

    private static void validateNameserverAddress(NameserverProperties nameserver) {
        if (nameserver == null || nameserver.getAddress() == null || nameserver.getAddress().isBlank()) {
            throw new IllegalStateException("discovery.type=nameserver 时必须配置 discovery.nameserver.address");
        }
        HostPort.require(nameserver.getAddress(), NAMESERVER_ADDRESS_CONFIG);
    }

    private static void validateRoute(
            GatewayConfig config,
            RouteProperties route,
            Set<String> businessPrefixes,
            DiscoveryType discoveryType) {
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
            if (!GatewayConfigMapper.hasStaticUpstream(route)) {
                throw new IllegalStateException(
                        "static 模式下需要 targetUrl 或 targetUrls，businessPrefix="
                                + route.getBusinessPrefix());
            }
            if (route.getTargetUrl() != null && !route.getTargetUrl().isBlank()) {
                validateTargetUrl(GatewayConfigMapper.stripWeightSuffix(route.getTargetUrl()));
            }
            if (route.getTargetUrls() != null) {
                for (String raw : route.getTargetUrls()) {
                    if (raw != null && !raw.isBlank()) {
                        validateTargetUrl(GatewayConfigMapper.stripWeightSuffix(raw.trim()));
                    }
                }
            }
        } else {
            if (route.getServiceName() == null || route.getServiceName().isBlank()) {
                throw new IllegalStateException("nameserver 模式下 serviceName 不能为空，businessPrefix="
                        + route.getBusinessPrefix());
            }
        }

        validateStripPrefix(config.resolveStripPrefix(route), route.getBusinessPrefix());
    }

    private static void validateTargetUrl(String targetUrl) {
        try {
            URI uri = new URI(targetUrl);
            String scheme = uri.getScheme();
            if (!HttpConstants.SCHEME_HTTP.equalsIgnoreCase(scheme)
                    && !HttpConstants.SCHEME_HTTPS.equalsIgnoreCase(scheme)) {
                throw new IllegalStateException("Gateway 路由 targetUrl 只支持 http/https：" + targetUrl);
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalStateException("Gateway 路由 targetUrl 必须包含主机地址：" + targetUrl);
            }
        } catch (URISyntaxException err) {
            throw new IllegalStateException("Gateway 路由 targetUrl 格式非法：" + targetUrl, err);
        }
    }

    private static void validateStripPrefix(String stripPrefix, String businessPrefix) {
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
}
