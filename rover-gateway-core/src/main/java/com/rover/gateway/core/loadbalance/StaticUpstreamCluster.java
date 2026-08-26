package com.rover.gateway.core.loadbalance;

import com.rover.common.constants.HttpConstants;
import com.rover.common.model.ServiceInstance;
import com.rover.gateway.core.config.GatewaySystemProperties;
import com.rover.gateway.core.route.RouteConfig;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Author: Daylight
 * Created: 2026-08-06 09:08:00
 * Description: 静态路由上游集群：把 targetUrl/targetUrls 收成实例列表，多地址可带 |weight
 */
public final class StaticUpstreamCluster {

    /** 默认权重。 */
    private static final String METADATA_SCHEME = "scheme";
    private static final String METADATA_BASE_URL = "baseUrl";

    /** 路由加载/热更新时整表替换，热路径只读。 */
    private static final AtomicReference<Map<String, List<ServiceInstance>>> SNAPSHOTS =
            new AtomicReference<>(Map.of());

    private StaticUpstreamCluster() {
    }

    /**
     * 路由表变更时调用：整表重算后一次性换上。
     * Admin 改路由、overlay、启动都走 GatewayRuntime.applyRoutes，不会漏。
     */
    public static void rebuild(List<RouteConfig> routes) {
        Map<String, List<ServiceInstance>> next = new HashMap<>();
        if (routes != null) {
            for (RouteConfig route : routes) {
                if (route == null) {
                    continue;
                }
                next.put(clusterKey(route), resolve(route));
            }
        }
        SNAPSHOTS.set(Map.copyOf(next));
    }

    /** 热路径读快照；对不上（换表瞬间）再现场 resolve，避免用到删掉的旧上游。 */
    public static List<ServiceInstance> instancesOf(RouteConfig route) {
        if (route == null) {
            return List.of();
        }
        List<ServiceInstance> cached = SNAPSHOTS.get().get(clusterKey(route));
        return cached != null ? cached : resolve(route);
    }

    /** 集群键，给 LB 按路由隔离计数。 */
    public static String clusterKey(RouteConfig route) {
        if (route == null) {
            return "static:unknown";
        }
        if (route.getId() != null && !route.getId().isBlank()) {
            return "static:" + route.getId();
        }
        return "static:" + route.getBusinessPrefix();
    }

    /** 是否配置了至少一个静态上游。 */
    public static boolean hasUpstreams(RouteConfig route) {
        return !resolve(route).isEmpty();
    }

    /**
     * 合并 targetUrl + targetUrls，去重后转成 ServiceInstance。
     * weight 默认 100；URL 写成 host:port|weight 可覆盖。
     */
    public static List<ServiceInstance> resolve(RouteConfig route) {
        if (route == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<ServiceInstance> instances = new ArrayList<>();
        addEndpoint(instances, seen, route.getTargetUrl(), route.getId(), route.getBusinessPrefix());
        if (route.getTargetUrls() != null) {
            for (String raw : route.getTargetUrls()) {
                addEndpoint(instances, seen, raw, route.getId(), route.getBusinessPrefix());
            }
        }
        return List.copyOf(instances);
    }

    private static void addEndpoint(
            List<ServiceInstance> out,
            Set<String> seen,
            String raw,
            String routeId,
            String businessPrefix) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        ParsedEndpoint parsed = parse(raw.trim());
        String dedupeKey = parsed.scheme() + "://"
                + parsed.host().toLowerCase(Locale.ROOT) + ":" + parsed.port();
        if (!seen.add(dedupeKey)) {
            return;
        }
        ServiceInstance instance = new ServiceInstance();
        instance.setServiceName(routeId == null || routeId.isBlank() ? businessPrefix : routeId);
        instance.setHost(parsed.host());
        instance.setPort(parsed.port());
        instance.setInstanceId(dedupeKey);
        instance.setHealthy(true);
        instance.setWeight(parsed.weight());
        instance.getMetadata().put(METADATA_SCHEME, parsed.scheme());
        instance.getMetadata().put(METADATA_BASE_URL, parsed.baseUrl());
        out.add(instance);
    }

    /** 解析 http://host:port 或 http://host:port|weight。 */
    static ParsedEndpoint parse(String raw) {
        String urlPart = raw;
        int weight = ServiceInstance.DEFAULT_WEIGHT;
        int bar = raw.lastIndexOf('|');
        if (bar > 0 && bar < raw.length() - 1) {
            String maybeWeight = raw.substring(bar + 1).trim();
            if (maybeWeight.chars().allMatch(Character::isDigit)) {
                urlPart = raw.substring(0, bar).trim();
                weight = Math.max(1, Integer.parseInt(maybeWeight));
            }
        }
        try {
            URI uri = URI.create(urlPart);
            String scheme = uri.getScheme();
            GatewaySystemProperties.requireUpstreamScheme(scheme, raw);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("必须包含主机: " + raw);
            }
            int port = uri.getPort();
            if (port < 0) {
                port = HttpConstants.SCHEME_HTTPS.equalsIgnoreCase(scheme)
                        ? HttpConstants.DEFAULT_HTTPS_PORT
                        : HttpConstants.DEFAULT_HTTP_PORT;
            }
            String baseUrl = scheme.toLowerCase(Locale.ROOT) + "://" + formatHost(uri.getHost()) + ":" + port;
            return new ParsedEndpoint(scheme.toLowerCase(Locale.ROOT), uri.getHost(), port, weight, baseUrl);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("非法上游地址: " + raw, ex);
        }
    }

    /** 选中实例后拼转发基址（带 scheme）。 */
    public static String baseUrlOf(ServiceInstance instance) {
        if (instance == null) {
            return null;
        }
        String base = instance.getMetadata() == null ? null : instance.getMetadata().get(METADATA_BASE_URL);
        if (base != null && !base.isBlank()) {
            return base;
        }
        return HttpConstants.SCHEME_HTTP + "://" + formatHost(instance.getHost()) + ":" + instance.getPort();
    }

    private static String formatHost(String host) {
        if (host == null) {
            return "";
        }
        return host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    record ParsedEndpoint(String scheme, String host, int port, int weight, String baseUrl) {
    }
}
