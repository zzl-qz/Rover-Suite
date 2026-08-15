package com.rover.gateway.core.server;

import com.rover.gateway.core.filter.DefaultFilterChain;
import com.rover.gateway.core.filter.GatewayRequestContext;
import com.rover.gateway.core.manage.GatewayManageApi;
import com.rover.gateway.core.runtime.GatewayRuntime;
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
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:58:00
 * Description: Netty 入站 Handler：管理口短路，其余请求走过滤器链
 */
@Slf4j
public class GatewayHttpServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    /** 网关运行时可变状态，含当前过滤器链和路由表。 */
    private final GatewayRuntime runtime;

    /** 同口管理 API 处理器。 */
    private final GatewayManageApi manageApi;

    /** 构造：绑定网关运行时并初始化同口管理 API。 */
    public GatewayHttpServerHandler(GatewayRuntime runtime) {
        this.runtime = runtime;
        this.manageApi = new GatewayManageApi(runtime);
    }

    /** 收到完整 HTTP 请求的入口：管理口短路，业务请求走过滤器链。 */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String requestPath = new QueryStringDecoder(request.uri()).path();
        // 管理口不进业务过滤器链
        if (manageApi.supports(requestPath)) {
            manageApi.handle(ctx, request, requestPath);
            return;
        }

        GatewayRequestContext context = new GatewayRequestContext(ctx, request, requestPath);
        try {
            new DefaultFilterChain(runtime.currentFilters()).doFilter(context);
        } catch (Exception err) {
            log.warn("Gateway filter chain error, requestPath={}", requestPath, err);
            if (!context.isCompleted()) {
                context.writeText(
                        HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        "Gateway filter chain error: " + err.getMessage());
            }
        }
    }

    /** 管道异常：body 过大返回 413，其它异常打日志并关连接。 */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof TooLongFrameException) {
            writeText(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "Request body too large");
            return;
        }
        log.warn("Gateway request handling error", cause);
        ctx.close();
    }

    /** 向客户端写纯文本响应。 */
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
