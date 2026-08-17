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
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-07 09:12:00
 * Description: HTTP/1.1 反向代理客户端：将请求转发到目标 URL，并回写后端响应
 */
@Slf4j
public class HttpProxyClient {

    /** 默认连接超时（毫秒）。 */
    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 3000;

    /** 默认单次请求超时（毫秒）。 */
    public static final int DEFAULT_REQUEST_TIMEOUT_MILLIS = 30000;

    /** 后端响应体最大缓冲；当前仍回写 FullHttpResponse，必须在聚合阶段设置硬上限。 */
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    /** 复用同一个 HttpClient，避免每次请求都新建连接池。 */
    private final HttpClient httpClient;
    /** 单次请求超时，可热更新。 */
    private final java.util.concurrent.atomic.AtomicLong requestTimeoutMillis;

    /** 在途上游请求数（正在等待后端响应的请求），近似代理客户端连接池使用情况。 */
    private final AtomicInteger inFlight = new AtomicInteger();

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

    /** 在途上游请求数，近似连接池使用情况（java.net.http 不暴露内部连接池状态）。 */
    public int getInFlightCount() {
        return inFlight.get();
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
     * 异步实现：用 sendAsync 发起，不占用调用线程等待上游，避免线程被阻塞导致的并发天花板。
     *
     * @return 代理结果 Future：最终状态码、上游往返耗时、是否连接失败/超时，方便指标统计
     */
    public CompletableFuture<ProxyResult> forwardAsync(
            ChannelHandlerContext ctx,
            FullHttpRequest request,
            String targetUrl) {
        long startNanos = System.nanoTime();
        inFlight.incrementAndGet();

        HttpRequest proxyRequest;
        try {
            proxyRequest = buildProxyRequest(ctx, request, targetUrl);
        } catch (Exception err) {
            inFlight.decrementAndGet();
            return CompletableFuture.completedFuture(handleError(ctx, err, targetUrl, startNanos));
        }

        return httpClient
                .sendAsync(proxyRequest, limitedByteArrayHandler(DEFAULT_MAX_RESPONSE_BYTES))
                .handle((proxyResponse, err) -> {
                    inFlight.decrementAndGet();
                    if (err != null) {
                        return handleError(ctx, err, targetUrl, startNanos);
                    }
                    writeProxyResponse(ctx, proxyResponse);
                    return new ProxyResult(proxyResponse.statusCode(), elapsedMillis(startNanos), false, false);
                });
    }

    /** 统一错误分类：把异常解包后按类型回写对应错误响应，并返回可统计的 ProxyResult。 */
    private ProxyResult handleError(
            ChannelHandlerContext ctx,
            Throwable err,
            String targetUrl,
            long startNanos) {
        Throwable cause = unwrap(err);
        String safeTarget = redactTargetUrl(targetUrl);
        if (cause instanceof HttpTimeoutException) {
            log.warn("Proxy request timeout, targetUrl={}", safeTarget, cause);
            writeProxyError(ctx, HttpResponseStatus.GATEWAY_TIMEOUT, "后端请求超时");
            return new ProxyResult(HttpResponseStatus.GATEWAY_TIMEOUT.code(), elapsedMillis(startNanos), false, true);
        }
        if (cause instanceof ResponseTooLargeException) {
            log.warn("Proxy response too large, targetUrl={}, limit={}",
                    safeTarget, DEFAULT_MAX_RESPONSE_BYTES);
            writeProxyError(ctx, HttpResponseStatus.BAD_GATEWAY, "后端响应体超过网关上限");
            return new ProxyResult(HttpResponseStatus.BAD_GATEWAY.code(), elapsedMillis(startNanos), false, false);
        }
        if (cause instanceof ConnectException) {
            log.warn("Proxy target connection error, targetUrl={}", safeTarget, cause);
            writeProxyError(ctx, HttpResponseStatus.BAD_GATEWAY, "后端连接失败");
            return new ProxyResult(HttpResponseStatus.BAD_GATEWAY.code(), elapsedMillis(startNanos), true, false);
        }
        if (cause instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            log.warn("Proxy request interrupted, targetUrl={}", safeTarget, cause);
            writeProxyError(ctx, HttpResponseStatus.BAD_GATEWAY, "代理请求被中断");
            return new ProxyResult(HttpResponseStatus.BAD_GATEWAY.code(), elapsedMillis(startNanos), true, false);
        }
        if (cause instanceof IllegalArgumentException) {
            log.warn("Invalid proxy target URL, targetUrl={}", safeTarget, cause);
            writeProxyError(ctx, HttpResponseStatus.BAD_GATEWAY, "目标 URL 非法");
            return new ProxyResult(HttpResponseStatus.BAD_GATEWAY.code(), elapsedMillis(startNanos), true, false);
        }
        log.warn("Proxy request IO error, targetUrl={}", safeTarget, cause);
        writeProxyError(ctx, HttpResponseStatus.BAD_GATEWAY, "后端响应异常");
        return new ProxyResult(HttpResponseStatus.BAD_GATEWAY.code(), elapsedMillis(startNanos), true, false);
    }

    /** 解包 CompletionException/ExecutionException，拿到真正的业务异常。 */
    private static Throwable unwrap(Throwable err) {
        Throwable cause = err;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** 单调时钟计时，纳秒转毫秒，负值夹到 0。 */
    private static long elapsedMillis(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    /** 一次代理转发的统计结果。 */
    public record ProxyResult(int statusCode, long upstreamCostMillis, boolean connectFail, boolean timeout) {
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
            // 未配置可信代理链时丢弃客户端自带 XFF，只写直连地址，避免后端信任伪造首段。
            builder.setHeader("X-Forwarded-For", clientIp);
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

    /** 代理失败时返回统一 JSON 错误；目标 URL 只写服务端脱敏日志，不回显给调用方。 */
    private void writeProxyError(
            ChannelHandlerContext ctx,
            HttpResponseStatus status,
            String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", status.code());
        error.put("message", message);
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

    private static HttpResponse.BodyHandler<byte[]> limitedByteArrayHandler(int maxBytes) {
        return responseInfo -> new LimitedByteArraySubscriber(maxBytes);
    }

    /** 在 java.net.http 交付数据时计数，超限立即取消订阅，避免先整包落堆再检查。 */
    static final class LimitedByteArraySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {

        private final int maxBytes;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private int received;

        LimitedByteArraySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            try {
                for (ByteBuffer buffer : buffers) {
                    int length = buffer.remaining();
                    if (length > maxBytes - received) {
                        subscription.cancel();
                        body.completeExceptionally(new ResponseTooLargeException(maxBytes));
                        return;
                    }
                    byte[] chunk = new byte[length];
                    buffer.get(chunk);
                    output.writeBytes(chunk);
                    received += length;
                }
                subscription.request(1);
            } catch (Throwable ex) {
                subscription.cancel();
                body.completeExceptionally(ex);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(output.toByteArray());
        }
    }

    private static final class ResponseTooLargeException extends RuntimeException {
        private ResponseTooLargeException(int maxBytes) {
            super("response body exceeds " + maxBytes + " bytes");
        }
    }

    /** 日志只保留 scheme/host/port/path，去掉 query 与 userInfo。 */
    public static String redactTargetUrl(String targetUrl) {
        if (targetUrl == null || targetUrl.isBlank()) {
            return targetUrl;
        }
        try {
            URI uri = URI.create(targetUrl);
            return new URI(
                    uri.getScheme(),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    null,
                    null).toString();
        } catch (Exception ignored) {
            int query = targetUrl.indexOf('?');
            return query < 0 ? targetUrl : targetUrl.substring(0, query);
        }
    }
}
