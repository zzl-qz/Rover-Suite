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
 *
 * 这个类是什么：过滤器链最后一个节点，负责把 HTTP 请求落到具体后端。
 * 核心职责：①按 businessPrefix 匹配路由；②静态模式拼 targetUrl，动态模式走发现+负载均衡；
 * ③调用 HttpProxyClient 完成真实转发并标记请求完成。
 * 被谁用：GatewayFilterAssembler 组装过滤器链时固定追加在末尾。
 */
@Slf4j
public class RouteAndProxyFilter implements Filter {

    /** 固定排在最后，保证前面插件过滤器都跑完再转发。 */
    public static final int ORDER = Integer.MAX_VALUE;

    /** 路由匹配器，按 businessPrefix 找目标规则。 */
    private final RouteMatcher routeMatcher;

    /** HTTP 反向代理客户端，负责把请求发到后端并回写响应。 */
    private final HttpProxyClient proxyClient;

    /** 上游发现模式：STATIC 用路由里的 targetUrl，NAMESERVER 走注册中心。 */
    private final DiscoveryType discoveryType;

    /** 服务发现实现，NAMESERVER 模式下查实例列表。 */
    private final ServiceDiscovery serviceDiscovery;

    /** 负载均衡器，从多个实例里挑一个。 */
    private final LoadBalancer loadBalancer;

    /**
     * 静态模式构造，只用路由里的 targetUrl，不连注册中心。
     *
     * @param routeMatcher 路由匹配器
     * @param proxyClient  HTTP 代理客户端
     */
    public RouteAndProxyFilter(RouteMatcher routeMatcher, HttpProxyClient proxyClient) {
        this(routeMatcher, proxyClient, DiscoveryType.STATIC, null, null);
    }

    /**
     * 全参数构造，支持静态和 Nameserver 动态发现两种模式。
     *
     * @param routeMatcher      路由匹配器
     * @param proxyClient       HTTP 代理客户端
     * @param discoveryType     发现模式，null 时默认 STATIC
     * @param serviceDiscovery  服务发现，NAMESERVER 模式必填
     * @param loadBalancer      负载均衡器，NAMESERVER 模式必填
     */
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

    /** @return 过滤器名称，用于日志和链路标识 */
    @Override
    public String getName() {
        return "route-and-proxy";
    }

    /** @return 执行顺序，固定为最大值，保证最后执行 */
    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * 匹配路由、解析目标地址、转发请求。
     * 匹配失败或没有可用上游时直接写错误响应并短路。
     *
     * @param context 网关请求上下文
     * @param chain   过滤器链（终端过滤器不再调用 chain.doFilter）
     */
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

    /**
     * 根据发现模式和路由配置拼出最终转发 URL。
     *
     * @param route       命中的路由
     * @param requestUri  原始 URI（含 query）
     * @param requestPath 不含 query 的路径
     * @return 完整目标 URL；无可用上游时返回 null
     */
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

    /** NAMESERVER 模式下通过发现+LB 拼出 http://host:port 基址。 */
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
