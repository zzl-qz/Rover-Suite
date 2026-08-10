package com.rover.gateway.core.runtime;

import com.rover.common.spi.Filter;
import com.rover.gateway.core.config.GatewayRuntimeConfigManager;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.discovery.ServiceDiscovery;
import com.rover.gateway.core.filter.FilterSettings;
import com.rover.gateway.core.filter.GatewayFilterAssembler;
import com.rover.gateway.core.loadbalance.LoadBalancer;
import com.rover.gateway.core.loadbalance.RandomLoadBalancer;
import com.rover.gateway.core.loadbalance.RoundRobinLoadBalancer;
import com.rover.gateway.core.proxy.HttpProxyClient;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteMatcher;
import com.rover.gateway.core.route.RouteOverlayStore;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:40:00
 * Description: Gateway 运行时可变状态，供热更新和管理口使用
 */
@Slf4j
@Getter
public class GatewayRuntime {

    private final int port;
    private final DiscoverySettings discoverySettings;
    private final FilterSettings filterSettings;
    private final HttpProxyClient proxyClient;
    private final ServiceDiscovery serviceDiscovery;
    private final DiscoveryType discoveryType;
    private final int connectTimeoutMillis;
    private final GatewayFilterAssembler assembler = new GatewayFilterAssembler();
    private final GatewayRuntimeConfigManager configManager;
    private final RouteOverlayStore routeOverlayStore;
    private final AtomicReference<RouteMatcher> routeMatcherRef = new AtomicReference<>();
    private final AtomicReference<List<Filter>> filters = new AtomicReference<>(List.of());
    private final AtomicReference<LoadBalancer> loadBalancer = new AtomicReference<>();
    private final AtomicReference<String> loadBalanceStrategy = new AtomicReference<>("round_robin");

    public GatewayRuntime(
            int port,
            List<RouteConfig> routes,
            int connectTimeoutMillis,
            int requestTimeoutMillis,
            FilterSettings filterSettings,
            DiscoverySettings discoverySettings,
            ServiceDiscovery serviceDiscovery,
            GatewayRuntimeConfigManager configManager) {
        this.port = port;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.discoverySettings = discoverySettings == null ? new DiscoverySettings() : discoverySettings;
        this.discoveryType = this.discoverySettings.getType();
        this.filterSettings = filterSettings == null ? new FilterSettings() : filterSettings;
        this.proxyClient = new HttpProxyClient(connectTimeoutMillis, requestTimeoutMillis);
        this.serviceDiscovery = serviceDiscovery;
        this.configManager = configManager;
        this.routeOverlayStore = new RouteOverlayStore();
        this.loadBalancer.set(new RoundRobinLoadBalancer());
        this.routeMatcherRef.set(new RouteMatcher(routes == null ? List.of() : routes));
        rebuildFilters();
    }

    public RouteMatcher getRouteMatcher() {
        return routeMatcherRef.get();
    }

    public List<Filter> currentFilters() {
        return filters.get();
    }

    public void applyFilterEnabled(boolean enabled) {
        filterSettings.setEnabled(enabled);
        rebuildFilters();
    }

    public void applyRequestTimeoutMillis(long timeoutMillis) {
        proxyClient.setRequestTimeoutMillis(timeoutMillis);
    }

    public void applyLoadBalanceStrategy(String strategy) {
        String normalized = strategy == null ? "round_robin" : strategy.trim().toLowerCase();
        LoadBalancer next = switch (normalized) {
            case "random" -> new RandomLoadBalancer();
            case "round_robin" -> new RoundRobinLoadBalancer();
            default -> throw new IllegalArgumentException("不支持的负载均衡策略: " + strategy);
        };
        loadBalanceStrategy.set(normalized);
        loadBalancer.set(next);
        rebuildFilters();
    }

    /** 整表替换路由：校验 → 热替换 matcher → 补订阅 → 落盘 overlay */
    public synchronized List<RouteConfig> applyRoutes(List<RouteConfig> routes) {
        List<RouteConfig> normalized = normalizeAndValidate(routes);
        routeMatcherRef.set(new RouteMatcher(normalized));
        rebuildFilters();
        watchServices(normalized);
        routeOverlayStore.save(normalized);
        log.info("路由已热更新, count={}, overlay={}",
                normalized.size(), routeOverlayStore.getPath().toAbsolutePath());
        return List.copyOf(normalized);
    }

    public List<RouteConfig> addOrReplaceRoute(RouteConfig route) {
        List<RouteConfig> current = new ArrayList<>(getRouteMatcher().listRoutes());
        String prefix = route.getBusinessPrefix();
        current.removeIf(item -> prefix != null && prefix.equals(item.getBusinessPrefix()));
        if (route.getId() != null && !route.getId().isBlank()) {
            current.removeIf(item -> route.getId().equals(item.getId()));
        }
        current.add(route);
        return applyRoutes(current);
    }

    public List<RouteConfig> removeRoute(String idOrPrefix) {
        if (idOrPrefix == null || idOrPrefix.isBlank()) {
            throw new IllegalArgumentException("删除路由需要 id 或 businessPrefix");
        }
        List<RouteConfig> current = new ArrayList<>(getRouteMatcher().listRoutes());
        boolean removed = current.removeIf(item ->
                idOrPrefix.equals(item.getId()) || idOrPrefix.equals(item.getBusinessPrefix()));
        if (!removed) {
            throw new IllegalArgumentException("未找到路由: " + idOrPrefix);
        }
        return applyRoutes(current);
    }

    private void watchServices(List<RouteConfig> routes) {
        if (discoveryType != DiscoveryType.NAMESERVER || serviceDiscovery == null) {
            return;
        }
        for (RouteConfig route : routes) {
            serviceDiscovery.ensureWatch(route.getServiceName(), route.getGroup());
        }
    }

    private List<RouteConfig> normalizeAndValidate(List<RouteConfig> routes) {
        if (routes == null) {
            return List.of();
        }
        List<RouteConfig> normalized = new ArrayList<>(routes.size());
        Set<String> prefixes = new HashSet<>();
        for (RouteConfig route : routes) {
            if (route == null) {
                continue;
            }
            RouteConfig copy = copyRoute(route);
            validateRoute(copy, prefixes);
            normalized.add(copy);
        }
        return normalized;
    }

    private void validateRoute(RouteConfig route, Set<String> prefixes) {
        if (route.getBusinessPrefix() == null || route.getBusinessPrefix().isBlank()) {
            throw new IllegalArgumentException("businessPrefix 不能为空");
        }
        if (!route.getBusinessPrefix().startsWith("/")) {
            throw new IllegalArgumentException("businessPrefix 必须以 / 开头: " + route.getBusinessPrefix());
        }
        if (!prefixes.add(route.getBusinessPrefix())) {
            throw new IllegalArgumentException("businessPrefix 重复: " + route.getBusinessPrefix());
        }
        if (discoveryType == DiscoveryType.STATIC) {
            if (route.getTargetUrl() == null || route.getTargetUrl().isBlank()) {
                throw new IllegalArgumentException("static 模式 targetUrl 不能为空: " + route.getBusinessPrefix());
            }
            validateTargetUrl(route.getTargetUrl());
        } else if (route.getServiceName() == null || route.getServiceName().isBlank()) {
            throw new IllegalArgumentException("nameserver 模式 serviceName 不能为空: " + route.getBusinessPrefix());
        }
        if (route.getStripPrefix() != null && !route.getStripPrefix().isBlank()) {
            if (!route.getStripPrefix().startsWith("/")) {
                throw new IllegalArgumentException("stripPrefix 必须以 / 开头");
            }
        }
    }

    private void validateTargetUrl(String targetUrl) {
        try {
            URI uri = URI.create(targetUrl);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("targetUrl 只支持 http/https: " + targetUrl);
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("targetUrl 必须包含主机: " + targetUrl);
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("targetUrl 非法: " + targetUrl, ex);
        }
    }

    private static RouteConfig copyRoute(RouteConfig route) {
        RouteConfig copy = new RouteConfig();
        copy.setId(trimToNull(route.getId()));
        copy.setBusinessPrefix(trimToNull(route.getBusinessPrefix()));
        copy.setTargetUrl(trimToNull(route.getTargetUrl()));
        copy.setServiceName(trimToNull(route.getServiceName()));
        copy.setGroup(trimToNull(route.getGroup()));
        copy.setStripPrefix(trimToNull(route.getStripPrefix()));
        return copy;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void rebuildFilters() {
        List<Filter> assembled = assembler.assemble(
                filterSettings,
                routeMatcherRef.get(),
                proxyClient,
                discoveryType,
                serviceDiscovery,
                loadBalancer.get());
        filters.set(assembled);
    }
}
