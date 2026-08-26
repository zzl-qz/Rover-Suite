package com.rover.gateway.bootstrap.config;

import com.rover.common.util.HostPort;
import com.rover.gateway.bootstrap.config.GatewayConfig.FilterProperties;
import com.rover.gateway.bootstrap.config.GatewayConfig.GatewayProperties;
import com.rover.gateway.bootstrap.config.GatewayConfig.NameserverProperties;
import com.rover.gateway.bootstrap.config.GatewayConfig.CircuitBreakerProperties;
import com.rover.gateway.bootstrap.config.GatewayConfig.RateLimitProperties;
import com.rover.gateway.bootstrap.config.GatewayConfig.RouteProperties;
import com.rover.gateway.core.config.GatewaySystemProperties;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.filter.circuit.CircuitBreakerSettings;
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
        validateProxyOutbound(config.getProxyOutboundOrDefault());
        validateIoTransport(config.getIoTransportOrDefault());
        validateFilterSettings(gateway.getFilters());
        validateRateLimit(gateway.getRateLimit());
        validateCircuitBreaker(gateway.getCircuitBreaker());
        validateObservability(config);

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

    private static void validateProxyOutbound(String outbound) {
        String normalized = outbound.trim().toLowerCase(Locale.ROOT);
        if (!"netty".equals(normalized) && !"jdk".equals(normalized)) {
            throw new IllegalArgumentException("proxy.outbound 仅支持 netty/jdk");
        }
    }

    private static void validateIoTransport(String ioTransport) {
        String normalized = ioTransport.trim().toLowerCase(Locale.ROOT);
        if (!"auto".equals(normalized) && !"nio".equals(normalized)
                && !"epoll".equals(normalized) && !"kqueue".equals(normalized)) {
            throw new IllegalArgumentException("server.ioTransport 仅支持 auto/nio/epoll/kqueue");
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

    private static void validateCircuitBreaker(CircuitBreakerProperties circuit) {
        if (circuit == null) {
            return;
        }
        if (circuit.getFailureThreshold() <= 0) {
            throw new IllegalArgumentException("circuitBreaker.failureThreshold 必须大于 0");
        }
        if (circuit.getOpenSeconds() <= 0 || circuit.getOpenSeconds() > 3600) {
            throw new IllegalArgumentException("circuitBreaker.openSeconds 必须在 1~3600 之间");
        }
        String recovery = circuit.getRecovery() == null
                ? CircuitBreakerSettings.ALL
                : circuit.getRecovery().trim().toLowerCase(Locale.ROOT);
        if (!CircuitBreakerSettings.ALL.equals(recovery) && !CircuitBreakerSettings.HALF.equals(recovery)) {
            throw new IllegalArgumentException("circuitBreaker.recovery 仅支持 all/half");
        }
    }

    private static void validateObservability(GatewayConfig config) {
        if (config.getMetricsWindowSecondsOrDefault() <= 0) {
            throw new IllegalArgumentException("metrics.windowSeconds 必须大于 0");
        }
        if (config.getTraceSlowThresholdMillisOrDefault() <= 0) {
            throw new IllegalArgumentException("trace.slowThresholdMillis 必须大于 0");
        }
        double sampleRate = config.getTraceSampleRateOrDefault();
        if (sampleRate < 0 || sampleRate > 1) {
            throw new IllegalArgumentException("trace.sampleRate 必须在 0~1 之间");
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
            String outbound = config.getProxyOutboundOrDefault();
            if (route.getTargetUrl() != null && !route.getTargetUrl().isBlank()) {
                validateTargetUrl(GatewayConfigMapper.stripWeightSuffix(route.getTargetUrl()), outbound);
            }
            if (route.getTargetUrls() != null) {
                for (String raw : route.getTargetUrls()) {
                    if (raw != null && !raw.isBlank()) {
                        validateTargetUrl(GatewayConfigMapper.stripWeightSuffix(raw.trim()), outbound);
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

    private static void validateTargetUrl(String targetUrl, String outbound) {
        try {
            URI uri = new URI(targetUrl);
            try {
                GatewaySystemProperties.requireUpstreamScheme(
                        uri.getScheme(), targetUrl, outbound);
            } catch (IllegalArgumentException err) {
                throw new IllegalStateException(err.getMessage(), err);
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
