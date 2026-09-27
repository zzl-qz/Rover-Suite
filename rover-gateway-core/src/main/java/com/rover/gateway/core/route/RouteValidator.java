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
        boolean versionedTargets = route.getTargets() != null && !route.getTargets().isEmpty();
        if (staticUpstream && versionedTargets) {
            throw new IllegalArgumentException(
                    "一条路由不能同时写 targets 和 targetUrl/targetUrls: " + route.getBusinessPrefix());
        }
        if (staticUpstream) {
            validateStaticUpstream(route);
        } else if (discoveryType.usesServiceDiscovery()) {
            validateTargets(route);
        } else {
            throw new IllegalArgumentException(
                    "static 模式需要 targetUrl 或 targetUrls: " + route.getBusinessPrefix());
        }
        if (route.getStripPrefix() != null && !route.getStripPrefix().isBlank()) {
            if (!route.getStripPrefix().startsWith("/")) {
                throw new IllegalArgumentException("stripPrefix 必须以 / 开头");
            }
        }
        validateStickyHeader(route);
    }

    /**
     * 校验动态上游的版本目标列表。
     *
     * 刻意收窄成「同一服务的多个版本」：所有 target 必须同 serviceName。
     * 否则这里会变成通用的多服务聚合路由，后续按版本看指标、按版本回滚都没有确定的语义。
     */
    private void validateTargets(RouteConfig route) {
        List<RouteTarget> targets = route.getTargets();
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException(
                    "动态路由需要 targets（serviceName/group/weight），或改成静态地址 targetUrl/targetUrls: "
                            + route.getBusinessPrefix());
        }
        Set<String> targetKeys = new HashSet<>();
        String serviceName = null;
        int totalWeight = 0;
        for (RouteTarget target : targets) {
            if (target == null) {
                throw new IllegalArgumentException("targets 不允许出现空元素: " + route.getBusinessPrefix());
            }
            if (target.serviceName() == null || target.serviceName().isBlank()) {
                throw new IllegalArgumentException("targets 每项的 serviceName 不能为空: " + route.getBusinessPrefix());
            }
            if (target.weight() < 0 || target.weight() > RouteTarget.MAX_WEIGHT) {
                throw new IllegalArgumentException(
                        "targets 权重必须在 0~" + RouteTarget.MAX_WEIGHT + " 之间: " + target.label());
            }
            if (serviceName == null) {
                serviceName = target.serviceName();
            } else if (!serviceName.equals(target.serviceName())) {
                throw new IllegalArgumentException(
                        "同一条路由的 targets 必须是同一个 serviceName 的不同 group: "
                                + serviceName + " 与 " + target.serviceName());
            }
            if (!targetKeys.add(target.clusterKey())) {
                throw new IllegalArgumentException("targets 出现重复目标: " + target.label());
            }
            totalWeight += target.weight();
        }
        if (totalWeight <= 0) {
            throw new IllegalArgumentException(
                    "targets 权重之和必须大于 0，否则这条路由没有版本可接流: " + route.getBusinessPrefix());
        }
    }

    /** 粘性头名要么不写，要么是合法的 HTTP 头字段名。 */
    private void validateStickyHeader(RouteConfig route) {
        String header = route.getStickyHeader();
        if (header == null || header.isBlank()) {
            return;
        }
        if (!header.matches("[A-Za-z0-9-]+")) {
            throw new IllegalArgumentException("stickyHeader 不是合法的请求头名: " + header);
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

    /**
     * 拷贝路由并 trim 各字符串字段。
     *
     * 公开是为了让「改一个版本的权重」这类局部修改能在副本上做——
     * 直接改 {@code RouteMatcher.listRoutes()} 返回的元素会动到正在接流的活对象，
     * 一旦后续校验失败或版本冲突，内存就已经被改坏了。
     */
    public static RouteConfig copyRoute(RouteConfig route) {
        RouteConfig copy = new RouteConfig();
        copy.setId(trimToNull(route.getId()));
        copy.setBusinessPrefix(RouteConfig.normalizePrefix(route.getBusinessPrefix()));
        copy.setTargetUrl(trimToNull(route.getTargetUrl()));
        copy.setStickyHeader(trimToNull(route.getStickyHeader()));
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
        List<RouteTarget> targets = new ArrayList<>();
        if (route.getTargets() != null) {
            for (RouteTarget target : route.getTargets()) {
                if (target != null) {
                    targets.add(new RouteTarget(target.serviceName(), target.group(), target.weight()));
                }
            }
        }
        copy.setTargets(targets);
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
