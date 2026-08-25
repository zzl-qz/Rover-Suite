package com.rover.gateway.core.server;

import com.rover.common.constants.HttpConstants;
import com.rover.common.config.ConfigValues;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Description: Netty 层 CORS 处理器：预检 OPTIONS 直接 204 应答，其余跨域请求放行并在
 *              响应写回时注入跨域头，位于业务过滤器链之前，保证 404/500/管理口等所有响应都带 CORS 头
 */
@Slf4j
public class CorsHandler extends ChannelDuplexHandler {

    /** channel 属性：当前请求已放行的 Origin，响应写回时据此补头。 */
    private static final AttributeKey<String> ALLOWED_ORIGIN =
            AttributeKey.valueOf("rover.cors.allowedOrigin");

    /** 预检/403 已经回了，后面的 body chunk 直接丢掉。 */
    private static final AttributeKey<Boolean> DRAIN_BODY =
            AttributeKey.valueOf("rover.cors.drainBody");

    private final CorsSettings settings;

    public CorsHandler(CorsSettings settings) {
        this.settings = settings;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!settings.isEnabled()) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (msg instanceof HttpContent content) {
            if (Boolean.TRUE.equals(ctx.channel().attr(DRAIN_BODY).get())) {
                boolean last = content instanceof LastHttpContent;
                content.release();
                if (last) {
                    ctx.channel().attr(DRAIN_BODY).set(null);
                }
                return;
            }
            ctx.fireChannelRead(msg);
            return;
        }
        if (!(msg instanceof HttpRequest request)) {
            ctx.fireChannelRead(msg);
            return;
        }

        String origin = request.headers().get(HttpHeaderNames.ORIGIN);
        // 同源请求（无 Origin 头，或 Origin 与 Host 一致）：原样放行，不做跨域处理
        if (origin == null || origin.isBlank() || isSameOrigin(request, origin)) {
            ctx.fireChannelRead(msg);
            return;
        }

        String allowedOrigin = settings.resolveAllowedOrigin(origin);
        if (allowedOrigin == null) {
            ctx.channel().attr(DRAIN_BODY).set(Boolean.TRUE);
            ReferenceCountUtil.release(msg);
            writeCorsError(ctx, "Origin not allowed: " + origin);
            return;
        }
        // 凭证模式下浏览器不接受 *，回显具体 Origin
        if ("*".equals(allowedOrigin) && settings.isCredentials()) {
            allowedOrigin = origin;
        }

        // 预检请求（OPTIONS + Access-Control-Request-Method）：网关直接应答，不进业务链
        if (HttpMethod.OPTIONS.equals(request.method())
                && request.headers().contains(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD)) {
            String requestedHeaders = request.headers()
                    .get(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS);
            ctx.channel().attr(DRAIN_BODY).set(Boolean.TRUE);
            ReferenceCountUtil.release(msg);
            writePreflightResponse(ctx, allowedOrigin, requestedHeaders);
            return;
        }

        // 真实跨域请求：记录允许的 Origin，放行业务链(入站塞入)
        ctx.channel().attr(ALLOWED_ORIGIN).set(allowedOrigin);
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        // 代理已改为 HttpResponse + HttpContent 流式回写；FullHttpResponse 仍覆盖错误/预检等整包路径。
        if (msg instanceof HttpResponse response) {
            String allowedOrigin = ctx.channel().attr(ALLOWED_ORIGIN).getAndRemove(); // 出站带出（只在首帧）
            if (allowedOrigin != null) {
                response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin);
                response.headers().add(HttpHeaderNames.VARY, HttpHeaderNames.ORIGIN);
                if (settings.isCredentials()) {
                    response.headers().set(
                            HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, ConfigValues.TRUE);
                }
            }
        }
        ctx.write(msg, promise);
    }

    /** 判断 Origin 是否与当前请求的 Host 同源（协议+主机+端口一致）。 */
    private boolean isSameOrigin(HttpRequest request, String origin) {
        String host = request.headers().get(HttpHeaderNames.HOST);
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            java.net.URI originUri = new java.net.URI(origin);
            String originHost = originUri.getHost();
            if (originHost == null || !originHost.equalsIgnoreCase(hostOf(host))) {
                return false;
            }
            int originPort = originUri.getPort();
            if (originPort < 0) {
                originPort = HttpConstants.SCHEME_HTTPS.equalsIgnoreCase(originUri.getScheme())
                        ? HttpConstants.DEFAULT_HTTPS_PORT
                        : HttpConstants.DEFAULT_HTTP_PORT;
            }
            return originPort == portOf(host);
        } catch (Exception ex) {
            return false;
        }
    }

    private static String hostOf(String hostHeader) {
        int colon = hostHeader.lastIndexOf(':');
        return colon > 0 ? hostHeader.substring(0, colon) : hostHeader;
    }

    private static int portOf(String hostHeader) {
        int colon = hostHeader.lastIndexOf(':');
        if (colon > 0 && colon < hostHeader.length() - 1) {
            try {
                return Integer.parseInt(hostHeader.substring(colon + 1));
            } catch (NumberFormatException ignored) {
                // 落到默认端口
            }
        }
        return 80;
    }

    /** 预检响应：204 + 允许方法/头/缓存时长。 */
    private void writePreflightResponse(
            ChannelHandlerContext ctx, String allowedOrigin, String requestedHeaders) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.NO_CONTENT,
                Unpooled.EMPTY_BUFFER);
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin);
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, settings.joinAllowedMethods());
        // 配置为 * 时回显预检请求声明的头，兼容带自定义头的跨域请求
        String allowHeaders = "*".equals(settings.joinAllowedHeaders()) && requestedHeaders != null
                ? requestedHeaders
                : settings.joinAllowedHeaders();
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, allowHeaders);
        if (settings.isCredentials()) {
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, ConfigValues.TRUE);
        }
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, settings.getMaxAgeSeconds());
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        log.debug("CORS preflight handled, origin={}", allowedOrigin);
    }

    /** 来源不允许时返回 403。 */
    private void writeCorsError(ChannelHandlerContext ctx, String message) {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.FORBIDDEN,
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpConstants.MEDIA_TYPE_TEXT_UTF8);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
