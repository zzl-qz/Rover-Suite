package com.rover.gateway.core.filter;

import com.rover.common.model.ServiceInstance;
import com.rover.common.spi.Filter;
import com.rover.common.spi.FilterChain;
import com.rover.common.spi.RequestContext;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.discovery.ServiceDiscovery;
import com.rover.gateway.core.loadbalance.LoadBalancer;
import com.rover.gateway.core.proxy.HttpProxyClient;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteMatcher;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 终端过滤器：路由匹配 + 选上游 + 真实转发
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

        String targetUrl = resolveTargetUrl(route, gatewayContext.getRequest().uri(), requestPath);
        if (targetUrl == null) {
            gatewayContext.writeText(
                    HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "No available upstream for route: " + route.getId());
            return;
        }

        gatewayContext.setRoute(route);
        gatewayContext.setTargetUrl(targetUrl);
        log.info(
                "Gateway route matched: routeId={}, businessPrefix={}, serviceName={}, targetUrl={}",
                route.getId(),
                route.getBusinessPrefix(),
                route.getServiceName(),
                targetUrl);

        int statusCode = proxyClient.forward(
                gatewayContext.getChannelContext(),
                gatewayContext.getRequest(),
                targetUrl);
        gatewayContext.setStatusCode(statusCode);
        gatewayContext.markCompleted();
    }

    private String resolveTargetUrl(RouteConfig route, String requestUri, String requestPath) {
        String baseUrl;
        if (discoveryType == DiscoveryType.NAMESERVER) {
            baseUrl = resolveDynamicBaseUrl(route);
            if (baseUrl == null) {
                return null;
            }
        } else {
            baseUrl = route.getTargetUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                return null;
            }
        }
        return joinUrl(baseUrl, requestUri, requestPath, route.getStripPrefix());
    }

    private String resolveDynamicBaseUrl(RouteConfig route) {
        if (serviceDiscovery == null || loadBalancer == null) {
            return null;
        }
        String serviceName = route.getServiceName();
        if (serviceName == null || serviceName.isBlank()) {
            return null;
        }
        List<ServiceInstance> instances = serviceDiscovery.getInstances(serviceName, route.getGroup());
        ServiceInstance chosen = loadBalancer.choose(serviceName, instances);
        if (chosen == null) {
            log.warn("动态发现无可用实例: serviceName={}, group={}", serviceName, route.getGroup());
            return null;
        }
        return "http://" + chosen.getHost() + ":" + chosen.getPort();
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
}
