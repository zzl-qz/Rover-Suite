/**
 * 作者：Daylight
 * 创建时间：2026-08-08 14:22:00
 * 描述：负责将 Gateway 接收到的 HTTP 请求代理到目标 URL
 */
package com.rover.gateway.core.proxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HttpProxyClient {

    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 3000;
    private static final int DEFAULT_REQUEST_TIMEOUT_MILLIS = 30000;

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public HttpProxyClient() {
        this(DEFAULT_CONNECT_TIMEOUT_MILLIS, DEFAULT_REQUEST_TIMEOUT_MILLIS);
    }

    public HttpProxyClient(int connectTimeoutMillis, int requestTimeoutMillis) {
        this.requestTimeout = Duration.ofMillis(requestTimeoutMillis);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * 转发请求到目标 URL，并将后端响应写回当前客户端连接。
     */
    public int forward(ChannelHandlerContext ctx, FullHttpRequest request, String targetUrl) {
        try {
            HttpRequest proxyRequest = buildProxyRequest(ctx, request, targetUrl);
            HttpResponse<byte[]> proxyResponse = httpClient.send(
                    proxyRequest,
                    HttpResponse.BodyHandlers.ofByteArray());
            writeProxyResponse(ctx, proxyResponse);
            return proxyResponse.statusCode();
        } catch (HttpTimeoutException err) {
            log.warn("Proxy request timeout, targetUrl={}", targetUrl, err);
            writeProxyError(ctx, HttpResponseStatus.GATEWAY_TIMEOUT, "后端请求超时", targetUrl);
            return HttpResponseStatus.GATEWAY_TIMEOUT.code();
        } catch (ConnectException err) {
            log.warn("Proxy target connection error, targetUrl={}", targetUrl, err);
            writeProxyError(ctx, HttpResponseStatus.BAD_GATEWAY, "后端连接失败", targetUrl);
            return HttpResponseStatus.BAD_GATEWAY.code();
        } catch (IOException err) {
            log.warn("Proxy request IO error, targetUrl={}", targetUrl, err);
            writeProxyError(ctx, HttpResponseStatus.BAD_GATEWAY, "后端响应异常", targetUrl);
            return HttpResponseStatus.BAD_GATEWAY.code();
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            log.warn("Proxy request interrupted, targetUrl={}", targetUrl, err);
            writeProxyError(ctx, HttpResponseStatus.BAD_GATEWAY, "代理请求被中断", targetUrl);
            return HttpResponseStatus.BAD_GATEWAY.code();
        } catch (IllegalArgumentException err) {
            log.warn("Invalid proxy target URL, targetUrl={}", targetUrl, err);
            writeProxyError(ctx, HttpResponseStatus.BAD_GATEWAY, "目标 URL 非法", targetUrl);
            return HttpResponseStatus.BAD_GATEWAY.code();
        }
    }

    private HttpRequest buildProxyRequest(
            ChannelHandlerContext ctx,
            FullHttpRequest request,
            String targetUrl) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(requestTimeout)
                .version(HttpClient.Version.HTTP_1_1);

        copyRequestHeaders(request, builder);
        addForwardedHeaders(ctx, request, builder);

        byte[] body = new byte[request.content().readableBytes()];
        request.content().getBytes(request.content().readerIndex(), body);
        HttpRequest.BodyPublisher bodyPublisher = body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);

        return builder.method(request.method().name(), bodyPublisher).build();
    }

    private void copyRequestHeaders(FullHttpRequest request, HttpRequest.Builder builder) {
        for (Map.Entry<String, String> header : request.headers()) {
            if (isHopByHopHeader(header.getKey()) || isManagedForwardHeader(header.getKey())) {
                continue;
            }
            builder.header(header.getKey(), header.getValue());
        }
    }

    private void addForwardedHeaders(
            ChannelHandlerContext ctx,
            FullHttpRequest request,
            HttpRequest.Builder builder) {
        String requestId = request.headers().get("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        builder.setHeader("X-Request-Id", requestId);
        builder.setHeader("X-Forwarded-Proto", "http");

        String host = request.headers().get(HttpHeaderNames.HOST);
        if (host != null && !host.isBlank()) {
            builder.setHeader("X-Forwarded-Host", host);
        }

        String clientIp = clientIp(ctx);
        if (clientIp != null && !clientIp.isBlank()) {
            builder.setHeader("X-Forwarded-For", forwardedFor(request, clientIp));
        }
    }

    private void writeProxyResponse(ChannelHandlerContext ctx, HttpResponse<byte[]> proxyResponse) {
        byte[] body = proxyResponse.body();
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(proxyResponse.statusCode()),
                Unpooled.wrappedBuffer(body));

        proxyResponse.headers().map().forEach((name, values) -> {
            if (!isHopByHopHeader(name)) {
                response.headers().set(name, values);
            }
        });
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        ctx.writeAndFlush(response);
    }

    private void writeProxyError(
            ChannelHandlerContext ctx,
            HttpResponseStatus status,
            String message,
            String targetUrl) {
        String responseBody = "{\"code\":" + status.code()
                + ",\"message\":\"" + escapeJson(message)
                + "\",\"targetUrl\":\"" + escapeJson(targetUrl) + "\"}";
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        ctx.writeAndFlush(response);
    }

    private String clientIp(ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress address) {
            return address.getAddress().getHostAddress();
        }
        return null;
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean isHopByHopHeader(String name) {
        String normalizedName = name.toLowerCase(Locale.ROOT);
        return "connection".equals(normalizedName)
                || "accept-encoding".equals(normalizedName)
                || "content-length".equals(normalizedName)
                || "expect".equals(normalizedName)
                || "host".equals(normalizedName)
                || "keep-alive".equals(normalizedName)
                || "proxy-authenticate".equals(normalizedName)
                || "proxy-authorization".equals(normalizedName)
                || "te".equals(normalizedName)
                || "trailer".equals(normalizedName)
                || "transfer-encoding".equals(normalizedName)
                || "upgrade".equals(normalizedName);
    }

    private boolean isManagedForwardHeader(String name) {
        String normalizedName = name.toLowerCase(Locale.ROOT);
        return "x-request-id".equals(normalizedName)
                || "x-forwarded-for".equals(normalizedName)
                || "x-forwarded-host".equals(normalizedName)
                || "x-forwarded-proto".equals(normalizedName);
    }

    private String forwardedFor(FullHttpRequest request, String clientIp) {
        String forwardedFor = request.headers().get("X-Forwarded-For");
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return clientIp;
        }
        return forwardedFor + ", " + clientIp;
    }
}
