package com.rover.gateway.core.filter;

import com.rover.common.constants.HttpConstants;
import com.rover.common.model.ServiceInstance;
import com.rover.common.spi.filter.Filter;
import com.rover.common.spi.filter.FilterChain;
import com.rover.common.spi.loadbalance.LoadBalanceContext;
import com.rover.common.spi.loadbalance.LoadBalancer;
import com.rover.common.spi.filter.RequestContext;
import com.rover.common.spi.discovery.ServiceDiscovery;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.filter.circuit.InstanceCircuitBreaker;
import com.rover.gateway.core.filter.retry.RetrySettings;
import com.rover.gateway.core.loadbalance.StaticUpstreamCluster;
import com.rover.gateway.core.metrics.MetricsRegistry;
import com.rover.gateway.core.proxy.HttpProxyClient;
import com.rover.gateway.core.proxy.InboundBodyPipe;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteMatcher;
import com.rover.gateway.core.trace.TracePhase;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    private final MetricsRegistry metricsRegistry;
    private final InstanceCircuitBreaker circuitBreaker;
    private final RetrySettings retry;

    public RouteAndProxyFilter(RouteMatcher routeMatcher, HttpProxyClient proxyClient) {
        this(routeMatcher, proxyClient, DiscoveryType.STATIC, null, null, null, null);
    }

    public RouteAndProxyFilter(
            RouteMatcher routeMatcher,
            HttpProxyClient proxyClient,
            DiscoveryType discoveryType,
            ServiceDiscovery serviceDiscovery,
            LoadBalancer loadBalancer) {
        this(routeMatcher, proxyClient, discoveryType, serviceDiscovery, loadBalancer, null, null);
    }

    public RouteAndProxyFilter(
            RouteMatcher routeMatcher,
            HttpProxyClient proxyClient,
            DiscoveryType discoveryType,
            ServiceDiscovery serviceDiscovery,
            LoadBalancer loadBalancer,
            MetricsRegistry metricsRegistry) {
        this(routeMatcher, proxyClient, discoveryType, serviceDiscovery, loadBalancer, metricsRegistry, null);
    }

    public RouteAndProxyFilter(
            RouteMatcher routeMatcher,
            HttpProxyClient proxyClient,
            DiscoveryType discoveryType,
            ServiceDiscovery serviceDiscovery,
            LoadBalancer loadBalancer,
            MetricsRegistry metricsRegistry,
            InstanceCircuitBreaker circuitBreaker) {
        this(routeMatcher, proxyClient, discoveryType, serviceDiscovery, loadBalancer, metricsRegistry,
                circuitBreaker, null);
    }

    public RouteAndProxyFilter(
            RouteMatcher routeMatcher,
            HttpProxyClient proxyClient,
            DiscoveryType discoveryType,
            ServiceDiscovery serviceDiscovery,
            LoadBalancer loadBalancer,
            MetricsRegistry metricsRegistry,
            InstanceCircuitBreaker circuitBreaker,
            RetrySettings retry) {
        this.routeMatcher = routeMatcher;
        this.proxyClient = proxyClient;
        this.discoveryType = discoveryType == null ? DiscoveryType.STATIC : discoveryType;
        this.serviceDiscovery = serviceDiscovery;
        this.loadBalancer = loadBalancer;
        this.metricsRegistry = metricsRegistry;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
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
        gatewayContext.markPhase(TracePhase.FILTER.phaseName(), Math.max(0, filterCostNanos));

        String requestPath = gatewayContext.getRequestPath();
        long routeStartNanos = System.nanoTime();
        RouteConfig route = routeMatcher.match(requestPath);
        gatewayContext.markPhase(TracePhase.ROUTE.phaseName(), System.nanoTime() - routeStartNanos);
        if (route == null) {
            gatewayContext.writeText(HttpResponseStatus.NOT_FOUND, "No route matched: " + requestPath);
            return CompletableFuture.completedFuture(null);
        }

        ChosenUpstream chosen = resolveUpstream(route, gatewayContext);
        if (chosen != null && chosen.circuitOpen()) {
            if (metricsRegistry != null) {
                metricsRegistry.recordReject(HttpConstants.REJECT_CIRCUIT_OPEN);
            }
            log.warn("All upstreams circuit-open, routeId={}, path={}, reason={}",
                    route.getId(), requestPath, HttpConstants.REJECT_CIRCUIT_OPEN);
            gatewayContext.writeText(
                    HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "Circuit open for route: " + route.getId(),
                    HttpConstants.REJECT_CIRCUIT_OPEN);
            return CompletableFuture.completedFuture(null);
        }
        if (chosen == null || chosen.baseUrl() == null) {
            if (metricsRegistry != null) {
                metricsRegistry.recordReject(HttpConstants.REJECT_NO_UPSTREAM);
            }
            log.warn("No available upstream, routeId={}, path={}, reason={}",
                    route.getId(), requestPath, HttpConstants.REJECT_NO_UPSTREAM);
            gatewayContext.writeText(
                    HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "No available upstream for route: " + route.getId(),
                    HttpConstants.REJECT_NO_UPSTREAM);
            return CompletableFuture.completedFuture(null);
        }

        gatewayContext.setRoute(route);
        boolean mayRetry = retry != null && retry.isEnabled() && hasSibling(route, chosen.instance());
        long proxyStartNanos = System.nanoTime();
        return forwardOnce(gatewayContext, route, requestPath, chosen, !mayRetry)
                .thenCompose(first -> retryOrFinish(gatewayContext, route, requestPath, chosen, first, mayRetry))
                .thenApply(attempt -> {
                    gatewayContext.markPhase(TracePhase.PROXY.phaseName(), System.nanoTime() - proxyStartNanos);
                    gatewayContext.setStatusCode(attempt.result().statusCode());
                    gatewayContext.setUpstreamHostPort(hostPortOf(attempt.instance()));
                    gatewayContext.setUpstreamCostMillis(attempt.result().upstreamCostMillis());
                    gatewayContext.setUpstreamConnectFail(attempt.result().connectFail());
                    gatewayContext.setUpstreamTimeout(attempt.result().timeout());
                    gatewayContext.markCompleted();
                    return null;
                });
    }

    private CompletableFuture<Attempt> retryOrFinish(
            GatewayRequestContext gatewayContext,
            RouteConfig route,
            String requestPath,
            ChosenUpstream firstChosen,
            Attempt first,
            boolean mayRetry) {
        if (!shouldRetry(mayRetry, first.result(), gatewayContext.getBodyPipe())) {
            if (mayRetry) {
                proxyClient.writeDeferredError(gatewayContext.getChannelContext(), first.result());
                if (gatewayContext.getBodyPipe().canReplay()) {
                    gatewayContext.getBodyPipe().abort();
                }
            }
            return CompletableFuture.completedFuture(first);
        }
        ChosenUpstream next = resolveUpstream(
                route, gatewayContext, Set.of(hostPortOf(firstChosen.instance())));
        if (next == null || next.circuitOpen() || next.baseUrl() == null) {
            proxyClient.writeDeferredError(gatewayContext.getChannelContext(), first.result());
            if (gatewayContext.getBodyPipe().canReplay()) {
                gatewayContext.getBodyPipe().abort();
            }
            return CompletableFuture.completedFuture(first);
        }
        if (metricsRegistry != null) {
            metricsRegistry.recordRetryConnect();
        }
        log.info(
                "连不上换台: from={}, to={}, routeId={}",
                hostPortOf(firstChosen.instance()),
                hostPortOf(next.instance()),
                route.getId());
        return forwardOnce(gatewayContext, route, requestPath, next, true);
    }

    private CompletableFuture<Attempt> forwardOnce(
            GatewayRequestContext gatewayContext,
            RouteConfig route,
            String requestPath,
            ChosenUpstream chosen,
            boolean writeClientError) {
        String targetUrl = joinUrl(
                chosen.baseUrl(),
                gatewayContext.getRequest().uri(),
                requestPath,
                route.getStripPrefix());
        gatewayContext.setTargetUrl(targetUrl);
        log.debug(
                "Gateway route matched: routeId={}, businessPrefix={}, serviceName={}, targetUrl={}, lb={}",
                route.getId(),
                route.getBusinessPrefix(),
                route.getServiceName(),
                HttpProxyClient.redactTargetUrl(targetUrl),
                loadBalancer == null ? "none" : loadBalancer.name());

        ServiceInstance instance = chosen.instance();
        if (loadBalancer != null && instance != null) {
            loadBalancer.onStart(instance);
        }
        return proxyClient
                .forwardAsync(
                        gatewayContext.getChannelContext(),
                        gatewayContext.getRequest(),
                        targetUrl,
                        gatewayContext.getBodyPipe(),
                        writeClientError)
                .whenComplete((result, err) -> {
                    if (loadBalancer != null && instance != null) {
                        loadBalancer.onComplete(instance);
                    }
                    if (circuitBreaker != null && instance != null) {
                        circuitBreaker.onResult(instance, isUpstreamAlive(result, err));
                    }
                })
                .thenApply(result -> new Attempt(result, instance));
    }

    /** 只救连不上，而且 body 还在。请求超时、5xx、已经发出去的都不换台。 */
    static boolean shouldRetry(boolean enabled, HttpProxyClient.ProxyResult result, InboundBodyPipe body) {
        if (!enabled || result == null || !result.connectFail() || result.timeout()) {
            return false;
        }
        return body != null && body.canReplay();
    }

    private ChosenUpstream resolveUpstream(RouteConfig route, GatewayRequestContext gatewayContext) {
        return resolveUpstream(route, gatewayContext, Set.of());
    }

    private ChosenUpstream resolveUpstream(
            RouteConfig route, GatewayRequestContext gatewayContext, Set<String> exclude) {
        if (loadBalancer == null) {
            // 极端兜底：无 LB 时静态只取第一个
            if (discoveryType == DiscoveryType.STATIC) {
                long discoveryStart = System.nanoTime();
                List<ServiceInstance> instances = StaticUpstreamCluster.instancesOf(route);
                gatewayContext.markPhase(TracePhase.DISCOVERY.phaseName(), System.nanoTime() - discoveryStart);
                if (instances.isEmpty()) {
                    return null;
                }
                List<ServiceInstance> candidates = applyCircuitAndExclude(instances, exclude);
                if (candidates == null) {
                    return exclude.isEmpty() ? ChosenUpstream.allOpen() : null;
                }
                if (candidates.isEmpty()) {
                    return null;
                }
                ServiceInstance first = candidates.get(0);
                return ChosenUpstream.of(StaticUpstreamCluster.baseUrlOf(first), first);
            }
            return null;
        }

        // 服务发现查询：从注册中心缓存/静态配置拉取候选实例列表
        String clusterKey = null;
        List<ServiceInstance> instances = null;
        long discoveryStart = System.nanoTime();
        if (discoveryType == DiscoveryType.NAMESERVER || discoveryType == DiscoveryType.NACOS) {
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
            instances = StaticUpstreamCluster.instancesOf(route);
        } else {
            log.warn("暂时没有该 discoveryType 类型， discoveryType is {}", discoveryType);
        }
        gatewayContext.markPhase(TracePhase.DISCOVERY.phaseName(), System.nanoTime() - discoveryStart);
        if (instances == null || instances.isEmpty()) {
            log.warn("无可用上游: discovery={}, clusterKey={}", discoveryType, clusterKey);
            return null;
        }

        List<ServiceInstance> candidates = applyCircuitAndExclude(instances, exclude);
        if (candidates == null) {
            return exclude.isEmpty() ? ChosenUpstream.allOpen() : null;
        }
        if (candidates.isEmpty()) {
            return null;
        }

        // 负载均衡选实例
        long lbStart = System.nanoTime();
        LoadBalanceContext lbContext = LoadBalanceContext.of(
                clusterKey,
                candidates,
                gatewayContext,
                resolveClientIp(gatewayContext));
        ServiceInstance chosen = loadBalancer.choose(lbContext);
        gatewayContext.markPhase(TracePhase.LOAD_BALANCE.phaseName(), System.nanoTime() - lbStart);
        if (chosen == null) {
            return null;
        }
        return ChosenUpstream.of(StaticUpstreamCluster.baseUrlOf(chosen), chosen);
    }

    /** 还有没有另一台能打。只看列表，不走 LB，避免预检查把轮询指针推走。 */
    private boolean hasSibling(RouteConfig route, ServiceInstance chosen) {
        if (chosen == null) {
            return false;
        }
        List<ServiceInstance> instances;
        if (discoveryType == DiscoveryType.NAMESERVER || discoveryType == DiscoveryType.NACOS) {
            if (serviceDiscovery == null || route.getServiceName() == null || route.getServiceName().isBlank()) {
                return false;
            }
            instances = serviceDiscovery.getInstances(route.getServiceName(), route.getGroup());
        } else if (discoveryType == DiscoveryType.STATIC) {
            instances = StaticUpstreamCluster.instancesOf(route);
        } else {
            return false;
        }
        List<ServiceInstance> candidates = applyCircuitAndExclude(instances, Set.of(hostPortOf(chosen)));
        return candidates != null && !candidates.isEmpty();
    }

    /**
     * 熔断过滤后再去掉已试过的实例。
     * 返回 null 表示全开（仅首次选点时变成 CIRCUIT_OPEN）。
     */
    private List<ServiceInstance> applyCircuitAndExclude(List<ServiceInstance> instances, Set<String> exclude) {
        List<ServiceInstance> candidates = instances;
        if (circuitBreaker != null) {
            candidates = circuitBreaker.filterAvailable(instances);
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
        }
        if (exclude == null || exclude.isEmpty()) {
            return candidates;
        }
        List<ServiceInstance> filtered = new ArrayList<>(candidates.size());
        for (ServiceInstance instance : candidates) {
            if (instance != null && !exclude.contains(hostPortOf(instance))) {
                filtered.add(instance);
            }
        }
        return filtered;
    }

    /** 2xx/4xx 算活着；连接失败、超时、5xx、异常算失败。给单测用。 */
    static boolean isUpstreamAlive(HttpProxyClient.ProxyResult result, Throwable err) {
        if (err != null || result == null) {
            return false;
        }
        return !result.connectFail() && !result.timeout() && result.statusCode() < 500;
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

    private record Attempt(HttpProxyClient.ProxyResult result, ServiceInstance instance) {
    }

    private record ChosenUpstream(String baseUrl, ServiceInstance instance, boolean circuitOpen) {
        static ChosenUpstream of(String baseUrl, ServiceInstance instance) {
            return new ChosenUpstream(baseUrl, instance, false);
        }

        static ChosenUpstream allOpen() {
            return new ChosenUpstream(null, null, true);
        }
    }
}
