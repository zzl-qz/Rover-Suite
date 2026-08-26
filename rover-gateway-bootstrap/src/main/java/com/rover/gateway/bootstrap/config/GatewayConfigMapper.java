package com.rover.gateway.bootstrap.config;

import com.rover.common.util.HostPort;
import com.rover.common.util.ServiceKeys;
import com.rover.gateway.bootstrap.config.GatewayConfig.CorsProperties;
import com.rover.gateway.bootstrap.config.GatewayConfig.FilterProperties;
import com.rover.gateway.bootstrap.config.GatewayConfig.NameserverProperties;
import com.rover.gateway.bootstrap.config.GatewayConfig.NacosProperties;
import com.rover.gateway.bootstrap.config.GatewayConfig.CircuitBreakerProperties;
import com.rover.gateway.bootstrap.config.GatewayConfig.RateLimitProperties;
import com.rover.gateway.bootstrap.config.GatewayConfig.RouteProperties;
import com.rover.gateway.core.config.GatewayDefaults;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.filter.FilterSettings;
import com.rover.gateway.core.filter.circuit.CircuitBreakerSettings;
import com.rover.gateway.core.filter.ratelimit.RateLimitSettings;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.server.CorsSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway YAML → core 运行时对象（CORS / Filter / Route / Discovery）。
 */
final class GatewayConfigMapper {

    private static final String NAMESERVER_ADDRESS_CONFIG = "discovery.nameserver.address";

    private GatewayConfigMapper() {
    }

    static CorsSettings toCorsSettings(GatewayConfig config) {
        CorsProperties cors = config.gatewayProperties().getCors();
        CorsSettings settings = new CorsSettings();
        if (cors == null) {
            return settings;
        }
        settings.setEnabled(cors.isEnabled());
        settings.setCredentials(cors.isCredentials());
        settings.setAllowedOrigins(cors.getAllowedOrigins() == null
                ? new ArrayList<>() : new ArrayList<>(cors.getAllowedOrigins()));
        if (cors.getAllowedMethods() != null && !cors.getAllowedMethods().isEmpty()) {
            settings.setAllowedMethods(new ArrayList<>(cors.getAllowedMethods()));
        }
        if (cors.getAllowedHeaders() != null && !cors.getAllowedHeaders().isEmpty()) {
            settings.setAllowedHeaders(new ArrayList<>(cors.getAllowedHeaders()));
        }
        if (cors.getMaxAgeSeconds() > 0) {
            settings.setMaxAgeSeconds(cors.getMaxAgeSeconds());
        }
        return settings;
    }

    static FilterSettings toFilterSettings(GatewayConfig config) {
        FilterProperties filterProperties = config.gatewayProperties().getFilters();
        FilterSettings settings = new FilterSettings();
        if (filterProperties == null) {
            return settings;
        }
        settings.setEnabled(filterProperties.isEnabled());
        settings.setPluginDir(filterProperties.getPluginDir());
        settings.setAccessLog(filterProperties.isAccessLog());
        settings.setClasses(filterProperties.getClasses() == null
                ? List.of()
                : List.copyOf(filterProperties.getClasses()));
        RateLimitProperties rate = config.gatewayProperties().getRateLimit();
        if (rate != null) {
            RateLimitSettings target = settings.getRateLimit();
            target.setEnabled(rate.isEnabled());
            target.setAlgorithm(rate.getAlgorithm());
            target.setKey(rate.getKey());
            target.setPermitsPerSecond(rate.getPermitsPerSecond());
            target.setBurst(rate.getBurst());
            target.setLimit(rate.getLimit());
            target.setWindowSeconds(rate.getWindowSeconds());
        }
        CircuitBreakerProperties circuit = config.gatewayProperties().getCircuitBreaker();
        if (circuit != null) {
            CircuitBreakerSettings target = settings.getCircuitBreaker();
            target.setEnabled(circuit.isEnabled());
            target.setFailureThreshold(circuit.getFailureThreshold());
            target.setOpenSeconds(circuit.getOpenSeconds());
            target.setRecovery(circuit.getRecovery());
        }
        if (config.gatewayProperties().getRetry() != null) {
            settings.getRetry().setEnabled(config.gatewayProperties().getRetry().isEnabled());
        }
        return settings;
    }

    static List<RouteConfig> toRouteConfigs(GatewayConfig config) {
        List<RouteProperties> routes = config.gatewayProperties().getRoutes();
        if (routes == null || routes.isEmpty()) {
            return List.of();
        }

        DiscoveryType discoveryType = config.getDiscoveryType();
        List<RouteConfig> routeConfigs = new ArrayList<>(routes.size());
        for (RouteProperties route : routes) {
            if (route.getBusinessPrefix() == null || route.getBusinessPrefix().isBlank()) {
                continue;
            }
            if (discoveryType == DiscoveryType.STATIC && !hasStaticUpstream(route)) {
                continue;
            }
            if ((discoveryType == DiscoveryType.NAMESERVER || discoveryType == DiscoveryType.NACOS)
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
            routeConfig.setStripPrefix(config.resolveStripPrefix(route));
            routeConfigs.add(routeConfig);
        }
        return routeConfigs;
    }

    static DiscoverySettings toDiscoverySettings(GatewayConfig config) {
        DiscoverySettings settings = new DiscoverySettings();
        DiscoveryType type = config.getDiscoveryType();
        settings.setType(type);

        NameserverProperties nameserver = config.gatewayProperties().getDiscovery().getNameserver();
        if (nameserver != null) {
            HostPort address = type == DiscoveryType.NAMESERVER
                    ? HostPort.require(nameserver.getAddress(), NAMESERVER_ADDRESS_CONFIG)
                    : HostPort.parseOrNull(nameserver.getAddress(), NAMESERVER_ADDRESS_CONFIG);
            if (address != null) {
                settings.setNameserverHost(address.host());
                settings.setNameserverPort(address.port());
            }
            settings.setNameserverToken(nameserver.getToken());
            long reconcile = nameserver.getReconcileIntervalMs() <= 0
                    ? GatewayDefaults.RECONCILE_INTERVAL_MILLIS
                    : nameserver.getReconcileIntervalMs();
            settings.setReconcileIntervalMs(reconcile);
        }

        if (type == DiscoveryType.NAMESERVER) {
            settings.setSubscribeServices(collectSubscribeServices(config));
        }
        NacosProperties nacos = config.gatewayProperties().getDiscovery().getNacos();
        if (nacos != null) {
            Map<String, String> provider = new LinkedHashMap<>();
            provider.put("serverAddr", nacos.getServerAddr());
            provider.put("namespace", nacos.getNamespace());
            provider.put("username", nacos.getUsername());
            provider.put("password", nacos.getPassword());
            provider.put("timeoutMs", Long.toString(nacos.getTimeoutMs()));
            settings.setProviderProperties(provider);
        }
        if (type == DiscoveryType.NACOS) {
            settings.setSubscribeServices(collectSubscribeServices(config));
        }
        return settings;
    }

    private static List<DiscoverySettings.ServiceSubscribeSpec> collectSubscribeServices(GatewayConfig config) {
        Map<String, DiscoverySettings.ServiceSubscribeSpec> unique = new LinkedHashMap<>();
        for (RouteConfig route : toRouteConfigs(config)) {
            if (route.getServiceName() == null || route.getServiceName().isBlank()) {
                continue;
            }
            String key = ServiceKeys.serviceGroup(route.getServiceName(), route.getGroup());
            DiscoverySettings.ServiceSubscribeSpec spec = new DiscoverySettings.ServiceSubscribeSpec();
            spec.setServiceName(route.getServiceName());
            spec.setGroup(route.getGroup());
            unique.putIfAbsent(key, spec);
        }
        return new ArrayList<>(unique.values());
    }

    static boolean hasStaticUpstream(RouteProperties route) {
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

    static List<String> copyTargetUrls(List<String> source) {
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

    static String stripWeightSuffix(String raw) {
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
