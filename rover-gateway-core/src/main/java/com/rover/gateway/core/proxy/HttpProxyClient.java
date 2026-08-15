package com.rover.gateway.core.proxy;

import com.rover.common.json.JsonCodec;
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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-07 09:12:00
 * Description: HTTP/1.1 反向代理客户端：将请求转发到目标 URL，并回写后端响应
 */
@Slf4j
public class HttpProxyClient {

    /** 默认连接超时（毫秒）。 */
    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 3000;

    /** 默认单次请求超时（毫秒）。 */
    private static final int DEFAULT_REQUEST_TIMEOUT_MILLIS = 30000;

    /** 复用同一个 HttpClient，避免每次请求都新建连接池。 */
    private final HttpClient httpClient;
    /** 单次请求超时，可热更新。 */
    private final java.util.concurrent.atomic.AtomicLong requestTimeoutMillis;

    /** 使用默认连接/请求超时构造。 */
    public HttpProxyClient() {
        this(DEFAULT_CONNECT_TIMEOUT_MILLIS, DEFAULT_REQUEST_TIMEOUT_MILLIS);
    }

    /** 指定连接/请求超时构造。 */
    public HttpProxyClient(int connectTimeoutMillis, int requestTimeoutMillis) {
        this.requestTimeoutMillis = new java.util.concurrent.atomic.AtomicLong(requestTimeoutMillis);
        // 强制 HTTP/1.1，避免部分后端对协议升级兼容不好。
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** 当前请求超时（毫秒）。 */
    public long getRequestTimeoutMillis() {
        return requestTimeoutMillis.get();
    }

    /** 热更新请求超时，必须大于 0。 */
    public void setRequestTimeoutMillis(long timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("requestTimeoutMillis 必须大于 0");
        }
        this.requestTimeoutMillis.set(timeoutMillis);
    }

    /**
     * 转发请求到目标 URL，并将后端响应写回当前客户端连接。
     *
     * @return 最终给客户端的 HTTP 状态码，方便链路日志统计
     */
    public int forward(ChannelHandlerContext ctx, FullHttpRequest request, String targetUrl) {
        try {
            HttpRequest proxyRequest = buildProxyRequest(ctx, request, targetUrl);
            // 同步发送；当前跑在业务线程池，不会直接堵死 Netty IO 线程。
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

    /** 把客户端原始请求改造成发给后端的代理请求。 */
    private HttpRequest buildProxyRequest(
            ChannelHandlerContext ctx,
            FullHttpRequest request,
            String targetUrl) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(Duration.ofMillis(requestTimeoutMillis.get()))
                .version(HttpClient.Version.HTTP_1_1);

        // 先复制业务 Header，再补网关自己的转发头。
        copyRequestHeaders(request, builder);
        addForwardedHeaders(ctx, request, builder);

        // 原样带上请求体，支持 POST/PUT 等带 body 的方法。
        byte[] body = new byte[request.content().readableBytes()];
        request.content().getBytes(request.content().readerIndex(), body);
        HttpRequest.BodyPublisher bodyPublisher = body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);

        return builder.method(request.method().name(), bodyPublisher).build();
    }

    /**
     * 复制客户端业务 Header。
     * Authorization、Cookie、Content-Type 等会保留；
     * Host、Content-Length、X-Forwarded-* 等由网关自己处理，不原样透传。
     */
    private void copyRequestHeaders(FullHttpRequest request, HttpRequest.Builder builder) {
        for (Map.Entry<String, String> header : request.headers()) {
            if (isHopByHopHeader(header.getKey()) || isManagedForwardHeader(header.getKey())) {
                continue;
            }
            builder.header(header.getKey(), header.getValue());
        }
    }

    /** 补充网关标准转发头，方便后端识别原始来源。 */
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
            // 如果上游已经带了 X-Forwarded-For，就追加当前客户端 IP。
            builder.setHeader("X-Forwarded-For", forwardedFor(request, clientIp));
        }
    }

    /** 把后端响应原样回写给调用方。 */
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

    /** 代理失败时返回统一 JSON 错误，方便排查目标地址。 */
    private void writeProxyError(
            ChannelHandlerContext ctx,
            HttpResponseStatus status,
            String message,
            String targetUrl) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", status.code());
        error.put("message", message);
        error.put("targetUrl", targetUrl);
        byte[] body = JsonCodec.toJson(error).getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        ctx.writeAndFlush(response);
    }

    private String clientIp(ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress address) {
            return address.getAddress().getHostAddress();
        }
        return null;
    }

    /** 协议层 Header，代理时不原样转发，避免兼容问题。 */
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

    /** 这些转发头由 Gateway 统一生成，避免和客户端原始值冲突。 */
    private boolean isManagedForwardHeader(String name) {
        String normalizedName = name.toLowerCase(Locale.ROOT);
        return "x-request-id".equals(normalizedName)
                || "x-forwarded-for".equals(normalizedName)
                || "x-forwarded-host".equals(normalizedName)
                || "x-forwarded-proto".equals(normalizedName);
    }

    /** 组装 X-Forwarded-For：保留上游链路，再追加当前直连客户端 IP。 */
    private String forwardedFor(FullHttpRequest request, String clientIp) {
        String forwardedFor = request.headers().get("X-Forwarded-For");
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return clientIp;
        }
        return forwardedFor + ", " + clientIp;
    }
}
