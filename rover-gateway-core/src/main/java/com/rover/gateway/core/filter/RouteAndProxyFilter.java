package com.rover.gateway.core.filter;

import com.rover.common.model.ServiceInstance;
import com.rover.common.spi.filter.Filter;
import com.rover.common.spi.filter.FilterChain;
import com.rover.common.spi.loadbalance.LoadBalanceContext;
import com.rover.common.spi.loadbalance.LoadBalancer;
import com.rover.common.spi.filter.RequestContext;
import com.rover.common.spi.discovery.ServiceDiscovery;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.loadbalance.StaticUpstreamCluster;
import com.rover.gateway.core.proxy.HttpProxyClient;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteMatcher;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 终端过滤器：路由匹配 + 选上游 + 真实转发，静态多 IP / Nameserver 动态实例统一走 LoadBalancer
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
    public CompletableFuture<Void> doFilter(RequestContext context, FilterChain chain) {
        GatewayRequestContext gatewayContext = (GatewayRequestContext) context;
        if (gatewayContext.isCompleted()) {
            return CompletableFuture.completedFuture(null);
        }

        // Filter 链（前置）：从请求开始到本终端过滤器执行，扣除已标记阶段（receive）
        long filterStartNanos = System.nanoTime();
        long filterCostNanos = filterStartNanos - gatewayContext.getStartNanos() - gatewayContext.phaseCostSumNanos();
        gatewayContext.markPhase("filter", Math.max(0, filterCostNanos));

        String requestPath = gatewayContext.getRequestPath();
        long routeStartNanos = System.nanoTime();
        RouteConfig route = routeMatcher.match(requestPath);
        gatewayContext.markPhase("route", System.nanoTime() - routeStartNanos);
        if (route == null) {
            gatewayContext.writeText(HttpResponseStatus.NOT_FOUND, "No route matched: " + requestPath);
            return CompletableFuture.completedFuture(null);
        }

        ChosenUpstream chosen = resolveUpstream(route, gatewayContext);
        if (chosen == null || chosen.baseUrl() == null) {
            gatewayContext.writeText(
                    HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "No available upstream for route: " + route.getId());
            return CompletableFuture.completedFuture(null);
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
                HttpProxyClient.redactTargetUrl(targetUrl),
                loadBalancer == null ? "none" : loadBalancer.name());

        ServiceInstance instance = chosen.instance();
        if (loadBalancer != null && instance != null) {
            // 告知 LB 实例开始占用（目前仅最少连接算法使用，其余为空实现）。
            loadBalancer.onStart(instance);
        }
        long proxyStartNanos = System.nanoTime();
        return proxyClient
                .forwardAsync(gatewayContext.getChannelContext(), gatewayContext.getRequest(), targetUrl)
                .whenComplete((result, err) -> {
                    // finally 语义：无论上游成功/失败，都释放 LB 对实例的占用。
                    if (loadBalancer != null && instance != null) {
                        loadBalancer.onComplete(instance);
                    }
                })
                .thenApply(result -> {
                    gatewayContext.markPhase("proxy", System.nanoTime() - proxyStartNanos);
                    gatewayContext.setStatusCode(result.statusCode());
                    // 回填上游信息，供 MetricsFilter 做上游维度统计
                    gatewayContext.setUpstreamHostPort(hostPortOf(instance));
                    gatewayContext.setUpstreamCostMillis(result.upstreamCostMillis());
                    gatewayContext.setUpstreamConnectFail(result.connectFail());
                    gatewayContext.setUpstreamTimeout(result.timeout());
                    gatewayContext.markCompleted();
                    return null;
                });
    }

    private ChosenUpstream resolveUpstream(RouteConfig route, GatewayRequestContext gatewayContext) {
        if (loadBalancer == null) {
            // 极端兜底：无 LB 时静态只取第一个
            if (discoveryType == DiscoveryType.STATIC) {
                long discoveryStart = System.nanoTime();
                List<ServiceInstance> instances = StaticUpstreamCluster.resolve(route);
                gatewayContext.markPhase("discovery", System.nanoTime() - discoveryStart);
                if (instances.isEmpty()) {
                    return null;
                }
                return new ChosenUpstream(StaticUpstreamCluster.baseUrlOf(instances.get(0)), instances.get(0));
            }
            return null;
        }

        // 服务发现查询：从注册中心缓存/静态配置拉取候选实例列表
        String clusterKey = null;
        List<ServiceInstance> instances = null;
        long discoveryStart = System.nanoTime();
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
        } else if (discoveryType == DiscoveryType.STATIC) {
            clusterKey = StaticUpstreamCluster.clusterKey(route);
            instances = StaticUpstreamCluster.resolve(route);
        } else {
            log.warn("暂时没有该 discoveryType 类型， discoveryType is {}", discoveryType);
        }
        gatewayContext.markPhase("discovery", System.nanoTime() - discoveryStart);
        if (instances == null || instances.isEmpty()) {
            log.warn("无可用上游: discovery={}, clusterKey={}", discoveryType, clusterKey);
            return null;
        }

        // 负载均衡选实例
        long lbStart = System.nanoTime();
        LoadBalanceContext lbContext = LoadBalanceContext.of(
                clusterKey,
                instances,
                gatewayContext,
                resolveClientIp(gatewayContext));
        ServiceInstance chosen = loadBalancer.choose(lbContext);
        gatewayContext.markPhase("loadbalance", System.nanoTime() - lbStart);
        if (chosen == null) {
            return null;
        }
        return new ChosenUpstream(StaticUpstreamCluster.baseUrlOf(chosen), chosen);
    }

    private static String hostPortOf(ServiceInstance instance) {
        if (instance == null) {
            return null;
        }
        return instance.getHost() + ":" + instance.getPort();
    }

    private static String resolveClientIp(GatewayRequestContext context) {
        // 未配置可信代理列表前，只信任直连地址；客户端可自行伪造 X-Forwarded-For。
        SocketAddress remote = context.getChannelContext().channel().remoteAddress();
        if (remote instanceof InetSocketAddress inet) {
            return inet.getAddress() == null ? inet.getHostString() : inet.getAddress().getHostAddress();
        }
        return remote == null ? null : remote.toString();
    }

    private String joinUrl(String baseUrl, String requestUri, String requestPath, String stripPrefix) {
        String query = extractQuery(requestUri);
        String forwardPath = requestPath;
        String normalizedStripPrefix = normalizePrefix(stripPrefix);
        if (shouldStripPrefix(normalizedStripPrefix, requestPath)) {
            forwardPath = requestPath.substring(normalizedStripPrefix.length());
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

    private String normalizePrefix(String prefix) {
        if (prefix == null) {
            return null;
        }
        String normalized = prefix.trim();
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
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
