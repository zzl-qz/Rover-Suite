package com.rover.gateway.core.route;

import com.rover.common.model.ServiceInstance;
import com.rover.gateway.core.config.GatewaySystemProperties;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.loadbalance.StaticUpstreamCluster;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Author: Daylight
 * Created: 2026-08-09 16:02:00
 * Description: 路由清洗与校验器：normalizeAndValidate 一次完成过滤 null、拷贝裁剪与字段校验，返回可生效的路由副本
 */
public class RouteValidator {

    /** 当前发现模式，决定静态/动态两种路由的校验规则。 */
    private final DiscoveryType discoveryType;

    public RouteValidator(DiscoveryType discoveryType) {
        this.discoveryType = discoveryType;
    }

    /** 清洗并校验路由列表，过滤 null，返回可直接生效的路由副本。 */
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
        boolean staticUpstream = StaticUpstreamCluster.hasRawTargets(route);
        boolean namedService = route.getServiceName() != null && !route.getServiceName().isBlank();
        if (staticUpstream && namedService) {
            throw new IllegalArgumentException(
                    "一条路由不能同时写 serviceName 和 targetUrl/targetUrls: " + route.getBusinessPrefix());
        }
        if (staticUpstream) {
            validateStaticUpstream(route);
        } else if (discoveryType.usesServiceDiscovery()) {
            if (!namedService) {
                throw new IllegalArgumentException(
                        "请写 serviceName，或改成静态地址 targetUrl/targetUrls: " + route.getBusinessPrefix());
            }
        } else {
            throw new IllegalArgumentException(
                    "static 模式需要 targetUrl 或 targetUrls: " + route.getBusinessPrefix());
        }
        if (route.getStripPrefix() != null && !route.getStripPrefix().isBlank()) {
            if (!route.getStripPrefix().startsWith("/")) {
                throw new IllegalArgumentException("stripPrefix 必须以 / 开头");
            }
        }
    }

    private void validateStaticUpstream(RouteConfig route) {
        List<ServiceInstance> staticInstances = StaticUpstreamCluster.resolve(route);
        if (staticInstances.isEmpty()) {
            throw new IllegalArgumentException(
                    "静态上游没有合法地址: " + route.getBusinessPrefix());
        }
        if (route.getTargetUrl() != null && !route.getTargetUrl().isBlank()) {
            validateTargetUrl(StaticUpstreamCluster.stripWeightSuffix(route.getTargetUrl()));
        }
        if (route.getTargetUrls() != null) {
            for (String raw : route.getTargetUrls()) {
                if (raw != null && !raw.isBlank()) {
                    validateTargetUrl(StaticUpstreamCluster.stripWeightSuffix(raw.trim()));
                }
            }
        }
    }

    /** 校验 targetUrl：有主机；netty 只认 http，jdk 才放 https。 */
    private void validateTargetUrl(String targetUrl) {
        try {
            URI uri = URI.create(targetUrl);
            GatewaySystemProperties.requireUpstreamScheme(uri.getScheme(), targetUrl);
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
        copy.setBusinessPrefix(RouteConfig.normalizePrefix(route.getBusinessPrefix()));
        copy.setTargetUrl(trimToNull(route.getTargetUrl()));
        copy.setServiceName(trimToNull(route.getServiceName()));
        copy.setGroup(trimToNull(route.getGroup()));
        copy.setStripPrefix(RouteConfig.normalizePrefix(route.getStripPrefix()));
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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
