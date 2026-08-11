package com.rover.gateway.core.filter;

import com.rover.common.spi.RequestContext;
import com.rover.gateway.core.route.RouteConfig;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: Gateway 请求上下文，承载 Netty 请求和过滤器共享状态
 *
 * 这个类是什么：RequestContext 的 Gateway 实现，贯穿整条过滤器链。
 * 核心职责：封装 Netty 通道和 HTTP 请求；提供 attributes 供插件共享数据；
 * 记录路由匹配结果、目标 URL、状态码；支持 writeText 短路回写。
 * 被谁用：GatewayHttpServerHandler 创建；各 Filter 读写共享状态。
 */
@Getter
public class GatewayRequestContext implements RequestContext {

    /** Netty 通道上下文，用来把响应写回客户端。 */
    private final ChannelHandlerContext channelContext;

    /** 当前收到的完整 HTTP 请求。 */
    private final FullHttpRequest request;

    /** 请求路径，不含 query 参数。 */
    private final String requestPath;

    /** 请求开始处理的纳秒时间，用于统计耗时。 */
    private final long startNanos;

    /** 过滤器之间共享的自定义属性，比如鉴权结果、traceId 等。 */
    private final Map<String, Object> attributes = new HashMap<>();

    /** 命中的路由规则，路由匹配后才会有值。 */
    @Setter
    private RouteConfig route;

    /** 最终要转发的后端 URL。 */
    @Setter
    private String targetUrl;

    /** 最终返回给客户端的状态码。 */
    @Setter
    private Integer statusCode;

    /** 请求是否已经结束（响应已写回或不再继续转发）。 */
    private boolean completed;

    /**
     * @param channelContext Netty 通道上下文，用于写回响应
     * @param request        完整 HTTP 请求
     * @param requestPath    不含 query 的请求路径
     */
    public GatewayRequestContext(
            ChannelHandlerContext channelContext,
            FullHttpRequest request,
            String requestPath) {
        this.channelContext = channelContext;
        this.request = request;
        this.requestPath = requestPath;
        this.startNanos = System.nanoTime();
    }

    /** @param key 属性名 @return 属性值，不存在时 null */
    @Override
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    /** @param key 属性名 @param value 属性值 */
    @Override
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /** @return 请求是否已结束（响应已写回或不再继续转发） */
    @Override
    public boolean isCompleted() {
        return completed;
    }

    /** 标记请求已结束，后续过滤器不会再执行。 */
    @Override
    public void markCompleted() {
        this.completed = true;
    }

    /**
     * 供过滤器短路时直接回写文本响应。
     * 例如鉴权失败、路由不存在时，可以直接调用这个方法结束请求。
     *
     * @param status       HTTP 状态码
     * @param responseBody 响应正文（text/plain）
     */
    public void writeText(HttpResponseStatus status, String responseBody) {
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        channelContext.writeAndFlush(response);
        this.statusCode = status.code();
        // 标记完成后，后面的过滤器不会再执行。
        markCompleted();
    }
}
