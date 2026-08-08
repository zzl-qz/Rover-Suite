package com.rover.gateway.core.server;

import com.rover.common.spi.Filter;
import com.rover.gateway.core.filter.DefaultFilterChain;
import com.rover.gateway.core.filter.GatewayRequestContext;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:22:00
 * Description: 接收外部 HTTP 请求并交给过滤器链处理
 */
@Slf4j
public class GatewayHttpServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    /**
     * 启动时组装好的过滤器列表。
     * 每次请求都会基于这份列表新建一条 FilterChain，互不影响。
     */
    private final List<Filter> filters;

    public GatewayHttpServerHandler(List<Filter> filters) {
        this.filters = filters;
    }

    /**
     * Netty 收到完整 HTTP 请求后的入口。
     * 这里只做两件事：构建请求上下文，然后启动过滤器链。
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        // 去掉 query，只保留路径，方便路由匹配。
        String requestPath = new QueryStringDecoder(request.uri()).path();
        GatewayRequestContext context = new GatewayRequestContext(ctx, request, requestPath);
        try {
            // 每次请求新建一条链，避免并发下标互相干扰。
            new DefaultFilterChain(filters).doFilter(context);
        } catch (Exception err) {
            log.warn("Gateway filter chain error, requestPath={}", requestPath, err);
            // 过滤器抛异常且还没回写响应时，统一返回 500。
            if (!context.isCompleted()) {
                context.writeText(
                        HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        "Gateway filter chain error: " + err.getMessage());
            }
        }
    }

    /**
     * 处理请求链路异常。
     * 请求体超过 maxContentLengthBytes 时，Netty 会抛 TooLongFrameException。
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

    /** 异常路径下直接回写简单文本响应。 */
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
}
