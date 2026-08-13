package com.rover.gateway.core.filter;

import com.rover.common.model.ServiceInstance;
import com.rover.common.spi.Filter;
import com.rover.common.spi.FilterChain;
import com.rover.common.spi.LoadBalanceContext;
import com.rover.common.spi.LoadBalancer;
import com.rover.common.spi.RequestContext;
import com.rover.common.spi.ServiceDiscovery;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.loadbalance.StaticUpstreamCluster;
import com.rover.gateway.core.proxy.HttpProxyClient;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteMatcher;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 终端过滤器：路由匹配 + 选上游 + 真实转发
 *
 * 静态多 IP / Nameserver 动态实例都走同一套 LoadBalancer。
 */
@Slf4j
public class RouteAndProxyFilter implements Filter {

    public static final int ORDER = Integer.MAX_VALUE;

    private final RouteMatcher routeMatcher;
    private final HttpProxyClient proxyClient;
    private final DiscoveryType discoveryType;
    private final ServiceDiscovery serviceDiscovery;
    private final LoadBalancer loadBalancer;

    public RouteAndProxyFilter(RouteMatcher routeMatcher, HttpProxyClient proxyClient) {
        this(routeMatcher, proxyClient, DiscoveryType.STATIC, null, null);
    }

    public RouteAndProxyFilter(
            RouteMatcher routeMatcher,
            HttpProxyClient proxyClient,
            DiscoveryType discoveryType,
            ServiceDiscovery serviceDiscovery,
            LoadBalancer loadBalancer) {
        this.routeMatcher = routeMatcher;
        this.proxyClient = proxyClient;
        this.discoveryType = discoveryType == null ? DiscoveryType.STATIC : discoveryType;
        this.serviceDiscovery = serviceDiscovery;
        this.loadBalancer = loadBalancer;
    }

    @Override
    public String getName() {
        return "route-and-proxy";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void doFilter(RequestContext context, FilterChain chain) {
        GatewayRequestContext gatewayContext = (GatewayRequestContext) context;
        if (gatewayContext.isCompleted()) {
            return;
        }

        String requestPath = gatewayContext.getRequestPath();
        RouteConfig route = routeMatcher.match(requestPath);
        if (route == null) {
            gatewayContext.writeText(HttpResponseStatus.NOT_FOUND, "No route matched: " + requestPath);
            return;
        }

        ChosenUpstream chosen = resolveUpstream(route, gatewayContext);
        if (chosen == null || chosen.baseUrl() == null) {
            gatewayContext.writeText(
                    HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "No available upstream for route: " + route.getId());
            return;
        }

        String targetUrl = joinUrl(
                chosen.baseUrl(),
                gatewayContext.getRequest().uri(),
                requestPath,
                route.getStripPrefix());
        gatewayContext.setRoute(route);
        gatewayContext.setTargetUrl(targetUrl);
        log.info(
                "Gateway route matched: routeId={}, businessPrefix={}, serviceName={}, targetUrl={}, lb={}",
                route.getId(),
                route.getBusinessPrefix(),
                route.getServiceName(),
                targetUrl,
                loadBalancer == null ? "none" : loadBalancer.name());

        ServiceInstance instance = chosen.instance();
        if (loadBalancer != null && instance != null) {
            loadBalancer.onStart(instance);
        }
        try {
            int statusCode = proxyClient.forward(
                    gatewayContext.getChannelContext(),
                    gatewayContext.getRequest(),
                    targetUrl);
            gatewayContext.setStatusCode(statusCode);
            gatewayContext.markCompleted();
        } finally {
            if (loadBalancer != null && instance != null) {
                loadBalancer.onComplete(instance);
            }
        }
    }

    private ChosenUpstream resolveUpstream(RouteConfig route, GatewayRequestContext gatewayContext) {
        if (loadBalancer == null) {
            // 极端兜底：无 LB 时静态只取第一个
            if (discoveryType == DiscoveryType.STATIC) {
                List<ServiceInstance> instances = StaticUpstreamCluster.resolve(route);
                if (instances.isEmpty()) {
                    return null;
                }
                return new ChosenUpstream(StaticUpstreamCluster.baseUrlOf(instances.get(0)), instances.get(0));
            }
            return null;
        }

        String clusterKey;
        List<ServiceInstance> instances;
        if (discoveryType == DiscoveryType.NAMESERVER) {
            if (serviceDiscovery == null) {
                return null;
            }
            String serviceName = route.getServiceName();
            if (serviceName == null || serviceName.isBlank()) {
                return null;
            }
            clusterKey = serviceName;
            instances = serviceDiscovery.getInstances(serviceName, route.getGroup());
        } else {
            clusterKey = StaticUpstreamCluster.clusterKey(route);
            instances = StaticUpstreamCluster.resolve(route);
        }
        if (instances == null || instances.isEmpty()) {
            log.warn("无可用上游: discovery={}, clusterKey={}", discoveryType, clusterKey);
            return null;
        }

        LoadBalanceContext lbContext = LoadBalanceContext.of(
                clusterKey,
                instances,
                gatewayContext,
                resolveClientIp(gatewayContext));
        ServiceInstance chosen = loadBalancer.choose(lbContext);
        if (chosen == null) {
            return null;
        }
        return new ChosenUpstream(StaticUpstreamCluster.baseUrlOf(chosen), chosen);
    }

    private static String resolveClientIp(GatewayRequestContext context) {
        String forwarded = context.getRequest().headers().get("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        SocketAddress remote = context.getChannelContext().channel().remoteAddress();
        if (remote instanceof InetSocketAddress inet) {
            return inet.getAddress() == null ? inet.getHostString() : inet.getAddress().getHostAddress();
        }
        return remote == null ? null : remote.toString();
    }

    private String joinUrl(String baseUrl, String requestUri, String requestPath, String stripPrefix) {
        String query = extractQuery(requestUri);
        String forwardPath = requestPath;
        if (shouldStripPrefix(stripPrefix, requestPath)) {
            forwardPath = requestPath.substring(stripPrefix.length());
            if (forwardPath.isBlank()) {
                forwardPath = "/";
            }
        }
        return trimTrailingSlash(baseUrl) + normalizeForwardPath(forwardPath) + query;
    }

    private boolean shouldStripPrefix(String stripPrefix, String requestPath) {
        if (stripPrefix == null || stripPrefix.isBlank()) {
            return false;
        }
        return requestPath.equals(stripPrefix) || requestPath.startsWith(stripPrefix + "/");
    }

    private String extractQuery(String requestUri) {
        int queryIndex = requestUri.indexOf('?');
        if (queryIndex < 0) {
            return "";
        }
        return requestUri.substring(queryIndex);
    }

    private String trimTrailingSlash(String targetUrl) {
        if (targetUrl.endsWith("/")) {
            return targetUrl.substring(0, targetUrl.length() - 1);
        }
        return targetUrl;
    }

    private String normalizeForwardPath(String forwardPath) {
        if (forwardPath.startsWith("/")) {
            return forwardPath;
        }
        return "/" + forwardPath;
    }

    private record ChosenUpstream(String baseUrl, ServiceInstance instance) {
    }
}
