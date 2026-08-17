package com.rover.nameserver.core.manage;

import com.rover.common.constants.HttpConstants;
import com.rover.common.json.JsonCodec;
import com.rover.nameserver.core.clientapi.NameserverClientApi;
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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * 同一 HTTP 监听端口上的轻量路由：显式隔离 Client API 与管理 API 的路径和鉴权域。
 */
final class NameserverHttpApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final NameserverClientApi clientApi;
    private final NameserverManageApi manageApi;

    NameserverHttpApiHandler(NameserverClientApi clientApi, NameserverManageApi manageApi) {
        this.clientApi = Objects.requireNonNull(clientApi, "clientApi");
        this.manageApi = Objects.requireNonNull(manageApi, "manageApi");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String path = new QueryStringDecoder(request.uri()).path();
        if (clientApi.supports(path)) {
            ctx.writeAndFlush(clientApi.handle(request, path));
            return;
        }
        if (manageApi.supports(path)) {
            manageApi.handle(ctx, request, path);
            return;
        }
        ctx.writeAndFlush(notFound(path));
    }

    private static FullHttpResponse notFound(String path) {
        byte[] body = JsonCodec.toJson(Map.of(
                        "code", "NOT_FOUND",
                        "message", "unknown HTTP path: " + path))
                .getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.NOT_FOUND,
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpConstants.MEDIA_TYPE_JSON_UTF8);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        return response;
    }
}
