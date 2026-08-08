/**
 * 作者：Daylight
 * 创建时间：2026-08-08 14:22:00
 * 描述：处理 Gateway 接收到的外部 HTTP 请求
 */
package com.rover.gateway.core.server;

import com.rover.gateway.core.proxy.HttpProxyClient;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteMatcher;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.TooLongFrameException;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;


/**
 * 外部http请求处理器
 */
@Slf4j
public class GatewayHttpServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final RouteMatcher routeMatcher;
    private final HttpProxyClient proxyClient;

    public GatewayHttpServerHandler(RouteMatcher routeMatcher, HttpProxyClient proxyClient) {
        this.routeMatcher = routeMatcher;
        this.proxyClient = proxyClient;
    }

    /**
     * 接收前端或调用方发来的 HTTP 请求，根据业务前缀匹配静态目标 URL。
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        long startNanos = System.nanoTime();
        String requestPath = new QueryStringDecoder(request.uri()).path();
        log.info("Gateway received request: {} {}", request.method(), requestPath);

        RouteConfig route = routeMatcher.match(requestPath);
        if (route == null) {
            writeText(ctx, HttpResponseStatus.NOT_FOUND, "No route matched: " + requestPath);
            logAccess(request, requestPath, null, null, HttpResponseStatus.NOT_FOUND.code(), startNanos);
            return;
        }

        String targetUrl = buildTargetUrl(route, request.uri(), requestPath);
        log.info(
                "Gateway route matched: routeId={}, businessPrefix={}, targetUrl={}",
                route.getId(),
                route.getBusinessPrefix(),
                targetUrl);

        int statusCode = proxyClient.forward(ctx, request, targetUrl);
        logAccess(request, requestPath, route, targetUrl, statusCode, startNanos);
    }

    private String buildTargetUrl(RouteConfig route, String requestUri, String requestPath) {
        String query = extractQuery(requestUri);
        String forwardPath = requestPath;
        if (shouldStripPrefix(route.getStripPrefix(), requestPath)) {
            forwardPath = requestPath.substring(route.getStripPrefix().length());
            if (forwardPath.isBlank()) {
                forwardPath = "/";
            }
        }
        return trimTrailingSlash(route.getTargetUrl()) + normalizeForwardPath(forwardPath) + query;
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

    private void writeText(ChannelHandlerContext ctx, HttpResponseStatus status, String responseBody) {
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        ctx.writeAndFlush(response);
    }

    /**
     * 处理请求链路异常，后续在这里统一回写网关错误响应。
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof TooLongFrameException) {
            writeText(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "Request body too large");
            return;
        }
        log.warn("Gateway request handling error", cause);
        ctx.close();
    }

    private void logAccess(
            FullHttpRequest request,
            String requestPath,
            RouteConfig route,
            String targetUrl,
            int statusCode,
            long startNanos) {
        long costMillis = (System.nanoTime() - startNanos) / 1_000_000;
        log.info(
                "Gateway request completed: method={}, requestPath={}, routeId={}, businessPrefix={}, "
                        + "targetUrl={}, statusCode={}, costMillis={}",
                request.method(),
                requestPath,
                route == null ? "-" : route.getId(),
                route == null ? "-" : route.getBusinessPrefix(),
                targetUrl == null ? "-" : targetUrl,
                statusCode,
                costMillis);
    }
}
