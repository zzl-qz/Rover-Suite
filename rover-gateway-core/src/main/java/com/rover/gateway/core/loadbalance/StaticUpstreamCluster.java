package com.rover.gateway.core.loadbalance;

import com.rover.common.model.ServiceInstance;
import com.rover.gateway.core.route.RouteConfig;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Author: Daylight
 * Created: 2026-08-06 09:08:00
 * Description: 静态路由上游集群：把 targetUrl/targetUrls 收成实例列表，多地址可带 |weight
 */
public final class StaticUpstreamCluster {

    /** 默认权重。 */
    private static final int DEFAULT_WEIGHT = 100;

    private static final int HTTP_PORT = 80;

    private static final int HTTPS_PORT = 443;

    private StaticUpstreamCluster() {
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
        String dedupeKey = parsed.host().toLowerCase(Locale.ROOT) + ":" + parsed.port();
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
        instance.getMetadata().put("scheme", parsed.scheme());
        instance.getMetadata().put("baseUrl", parsed.baseUrl());
        out.add(instance);
    }

    /** 解析 http://host:port 或 http://host:port|weight。 */
    static ParsedEndpoint parse(String raw) {
        String urlPart = raw;
        int weight = DEFAULT_WEIGHT;
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
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("只支持 http/https: " + raw);
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("必须包含主机: " + raw);
            }
            int port = uri.getPort();
            if (port < 0) {
                port = "https".equalsIgnoreCase(scheme) ? HTTPS_PORT : HTTP_PORT;
            }
            String baseUrl = scheme.toLowerCase(Locale.ROOT) + "://" + uri.getHost() + ":" + port;
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
        String base = instance.getMetadata() == null ? null : instance.getMetadata().get("baseUrl");
        if (base != null && !base.isBlank()) {
            return base;
        }
        return "http://" + instance.getHost() + ":" + instance.getPort();
    }

    record ParsedEndpoint(String scheme, String host, int port, int weight, String baseUrl) {
    }
}
