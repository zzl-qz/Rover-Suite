package com.rover.gateway.core.filter;

import com.rover.common.spi.Filter;
import com.rover.common.spi.FilterChain;
import com.rover.common.spi.RequestContext;
import com.rover.gateway.core.proxy.HttpProxyClient;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteMatcher;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 内置终端过滤器，负责路由匹配、前缀重写和真实代理转发
 */
@Slf4j
public class RouteAndProxyFilter implements Filter {

    /** 固定放到过滤器链最后执行。 */
    public static final int ORDER = Integer.MAX_VALUE;

    private final RouteMatcher routeMatcher;
    private final HttpProxyClient proxyClient;

    public RouteAndProxyFilter(RouteMatcher routeMatcher, HttpProxyClient proxyClient) {
        this.routeMatcher = routeMatcher;
        this.proxyClient = proxyClient;
    }

    @Override
    public String getName() {
        return "route-and-proxy";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * 终端过滤器：匹配路由 -> 计算目标 URL -> 真实转发后端。
     * 这里一般不再调用 chain.doFilter，因为后面已经没有过滤器了。
     */
    @Override
    public void doFilter(RequestContext context, FilterChain chain) {
        GatewayRequestContext gatewayContext = (GatewayRequestContext) context;
        if (gatewayContext.isCompleted()) {
            return;
        }

        String requestPath = gatewayContext.getRequestPath();
        // 按业务前缀匹配路由，例如 /api/uu/**
        RouteConfig route = routeMatcher.match(requestPath);
        if (route == null) {
            gatewayContext.writeText(HttpResponseStatus.NOT_FOUND, "No route matched: " + requestPath);
            return;
        }

        // 结合 stripPrefix 生成最终后端地址。
        String targetUrl = buildTargetUrl(route, gatewayContext.getRequest().uri(), requestPath);
        gatewayContext.setRoute(route);
        gatewayContext.setTargetUrl(targetUrl);
        log.info(
                "Gateway route matched: routeId={}, businessPrefix={}, targetUrl={}",
                route.getId(),
                route.getBusinessPrefix(),
                targetUrl);

        // 真实发起 HTTP 请求，并把后端响应写回客户端。
        int statusCode = proxyClient.forward(
                gatewayContext.getChannelContext(),
                gatewayContext.getRequest(),
                targetUrl);
        gatewayContext.setStatusCode(statusCode);
        gatewayContext.markCompleted();
    }

    /**
     * 计算真实转发地址：
     * targetUrl + 去掉前缀后的路径 + 原始 query。
     */
    private String buildTargetUrl(RouteConfig route, String requestUri, String requestPath) {
        String query = extractQuery(requestUri);
        String forwardPath = requestPath;
        if (shouldStripPrefix(route.getStripPrefix(), requestPath)) {
            // 例如 stripPrefix=/api/uu，请求 /api/uu/admin/list -> /admin/list
            forwardPath = requestPath.substring(route.getStripPrefix().length());
            if (forwardPath.isBlank()) {
                forwardPath = "/";
            }
        }
        return trimTrailingSlash(route.getTargetUrl()) + normalizeForwardPath(forwardPath) + query;
    }

    /** 只有请求路径确实以 stripPrefix 开头时才去掉前缀。 */
    private boolean shouldStripPrefix(String stripPrefix, String requestPath) {
        if (stripPrefix == null || stripPrefix.isBlank()) {
            return false;
        }
        return requestPath.equals(stripPrefix) || requestPath.startsWith(stripPrefix + "/");
    }

    /** 保留原始 query，例如 ?id=1。 */
    private String extractQuery(String requestUri) {
        int queryIndex = requestUri.indexOf('?');
        if (queryIndex < 0) {
            return "";
        }
        return requestUri.substring(queryIndex);
    }

    /** 去掉 targetUrl 末尾多余斜杠，避免拼出双斜杠。 */
    private String trimTrailingSlash(String targetUrl) {
        if (targetUrl.endsWith("/")) {
            return targetUrl.substring(0, targetUrl.length() - 1);
        }
        return targetUrl;
    }

    /** 保证转发路径以 / 开头。 */
    private String normalizeForwardPath(String forwardPath) {
        if (forwardPath.startsWith("/")) {
            return forwardPath;
        }
        return "/" + forwardPath;
    }
}
