package com.rover.gateway.core.route;

import com.rover.common.model.ServiceInstance;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.loadbalance.StaticUpstreamCluster;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 路由清洗与校验器。
 *
 * 这个类是什么：把「过滤 null、拷贝裁剪、前缀/上游/转发字段校验」这些纯路由规则
 * 从 GatewayRuntime 里独立出来，让 Runtime 只保留可变状态与热更新编排。
 * 核心职责：normalizeAndValidate 一次完成清洗 + 校验，返回可直接生效的路由副本。
 * 被谁用：GatewayRuntime 在 applyRoutes 时调用。
 */
public class RouteValidator {

    /** 当前发现模式，决定静态/动态两种路由的校验规则。 */
    private final DiscoveryType discoveryType;

    public RouteValidator(DiscoveryType discoveryType) {
        this.discoveryType = discoveryType;
    }

    /**
     * 清洗并校验路由列表，过滤 null 条目。
     *
     * @param routes 原始路由列表
     * @return 清洗校验后的路由副本
     * @throws IllegalArgumentException 校验失败
     */
    public List<RouteConfig> normalizeAndValidate(List<RouteConfig> routes) {
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

    /** 校验单条路由：前缀格式、唯一性、静态/动态必填字段。 */
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
            if (!StaticUpstreamCluster.hasUpstreams(route)) {
                throw new IllegalArgumentException(
                        "static 模式需要 targetUrl 或 targetUrls: " + route.getBusinessPrefix());
            }
            // resolve 会解析并校验地址；再对原始配置校验，错误信息更直观
            List<ServiceInstance> staticInstances = StaticUpstreamCluster.resolve(route);
            if (staticInstances.isEmpty()) {
                throw new IllegalArgumentException(
                        "static 模式没有合法上游: " + route.getBusinessPrefix());
            }
            if (route.getTargetUrl() != null && !route.getTargetUrl().isBlank()) {
                validateTargetUrl(stripWeight(route.getTargetUrl()));
            }
            if (route.getTargetUrls() != null) {
                for (String raw : route.getTargetUrls()) {
                    if (raw != null && !raw.isBlank()) {
                        validateTargetUrl(stripWeight(raw.trim()));
                    }
                }
            }
        } else if (route.getServiceName() == null || route.getServiceName().isBlank()) {
            throw new IllegalArgumentException("nameserver 模式 serviceName 不能为空: " + route.getBusinessPrefix());
        }
        if (route.getStripPrefix() != null && !route.getStripPrefix().isBlank()) {
            if (!route.getStripPrefix().startsWith("/")) {
                throw new IllegalArgumentException("stripPrefix 必须以 / 开头");
            }
        }
    }

    /** 校验 targetUrl 必须是 http/https 且含主机。 */
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

    /** 拷贝路由并 trim 各字符串字段。 */
    private static RouteConfig copyRoute(RouteConfig route) {
        RouteConfig copy = new RouteConfig();
        copy.setId(trimToNull(route.getId()));
        copy.setBusinessPrefix(trimToNull(route.getBusinessPrefix()));
        copy.setTargetUrl(trimToNull(route.getTargetUrl()));
        copy.setServiceName(trimToNull(route.getServiceName()));
        copy.setGroup(trimToNull(route.getGroup()));
        copy.setStripPrefix(trimToNull(route.getStripPrefix()));
        List<String> urls = new ArrayList<>();
        if (route.getTargetUrls() != null) {
            for (String raw : route.getTargetUrls()) {
                String trimmed = trimToNull(raw);
                if (trimmed != null) {
                    urls.add(trimmed);
                }
            }
        }
        copy.setTargetUrls(urls);
        return copy;
    }

    private static String stripWeight(String raw) {
        int bar = raw.lastIndexOf('|');
        if (bar > 0 && bar < raw.length() - 1) {
            String maybeWeight = raw.substring(bar + 1).trim();
            if (maybeWeight.chars().allMatch(Character::isDigit)) {
                return raw.substring(0, bar).trim();
            }
        }
        return raw;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
