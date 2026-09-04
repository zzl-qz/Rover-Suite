package com.rover.gateway.core.proxy;

import com.rover.common.json.JsonCodec;
import com.rover.common.constants.HttpConstants;
import com.rover.gateway.core.config.GatewayDefaults;
import com.rover.gateway.core.config.GatewaySystemProperties;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AttributeKey;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
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
    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = GatewayDefaults.CONNECT_TIMEOUT_MILLIS;

    /** 默认单次请求超时（毫秒）。 */
    public static final int DEFAULT_REQUEST_TIMEOUT_MILLIS = GatewayDefaults.REQUEST_TIMEOUT_MILLIS;

    /** 后端响应体硬上限；流式回写时边收边计，超限取消订阅并关连接。 */
    public static final int DEFAULT_MAX_RESPONSE_BYTES = GatewayDefaults.MAX_RESPONSE_BODY_BYTES;

    /** 本连接是否已写出代理响应头；中途失败时不能再写 JSON 错包。 */
    private static final AttributeKey<Boolean> RESPONSE_STARTED =
            AttributeKey.valueOf("rover.proxy.responseStarted");

    /** 复用同一个 HttpClient，避免每次请求都新建连接池。outbound=jdk 才建。 */
    private final HttpClient httpClient;
    /** Netty 出站；outbound=netty 时走这条，JDK 客户端留着可切回。 */
    private final NettyUpstreamClient nettyClient;
    /** 单次请求超时，可热更新。 */
    private final java.util.concurrent.atomic.AtomicLong requestTimeoutMillis;

    /** 在途上游请求数（正在等待后端响应的请求），近似代理客户端连接池使用情况。 */
    private final AtomicInteger inFlight = new AtomicInteger();

    /** 使用默认连接/请求超时构造。 */
    public HttpProxyClient() {
        this(DEFAULT_CONNECT_TIMEOUT_MILLIS, DEFAULT_REQUEST_TIMEOUT_MILLIS);
    }

    /** 单测替身用，不建出站客户端。 */
    protected HttpProxyClient(Void unused) {
        this.requestTimeoutMillis = new java.util.concurrent.atomic.AtomicLong(DEFAULT_REQUEST_TIMEOUT_MILLIS);
        this.httpClient = null;
        this.nettyClient = null;
    }

    /** 指定连接/请求超时构造。出站实现看 -Drover.gateway.proxy.outbound，默认 netty。 */
    public HttpProxyClient(int connectTimeoutMillis, int requestTimeoutMillis) {
        this.requestTimeoutMillis = new java.util.concurrent.atomic.AtomicLong(requestTimeoutMillis);
        if (useNettyOutbound()) {
            this.httpClient = null;
            this.nettyClient = new NettyUpstreamClient(connectTimeoutMillis, requestTimeoutMillis);
            log.info("Gateway 出站代理: Netty（JDK HttpClient 仍可通过 outbound=jdk 切回）");
        } else {
            // 强制 HTTP/1.1，避免部分后端对协议升级兼容不好。这是第一版出站，压测踩过坑，别删。
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            this.nettyClient = null;
            log.info("Gateway 出站代理: JDK HttpClient HTTP/1.1");
        }
    }

    static boolean useNettyOutbound() {
        String raw = System.getProperty(GatewaySystemProperties.PROXY_OUTBOUND, "netty");
        return raw == null || !"jdk".equalsIgnoreCase(raw.trim());
    }

    /** 当前请求超时（毫秒）。 */
    public long getRequestTimeoutMillis() {
        return requestTimeoutMillis.get();
    }

    /** 在途上游请求数，近似连接池使用情况。 */
    public int getInFlightCount() {
        return nettyClient != null ? nettyClient.getInFlightCount() : inFlight.get();
    }

    /** 热更新请求超时，必须大于 0。 */
    public void setRequestTimeoutMillis(long timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("requestTimeoutMillis 必须大于 0");
        }
        this.requestTimeoutMillis.set(timeoutMillis);
        if (nettyClient != null) {
            nettyClient.setRequestTimeoutMillis(timeoutMillis);
        }
    }

    /** 关掉 Netty 连接池；JDK 模式空操作。 */
    public void close() {
        if (nettyClient != null) {
            nettyClient.close();
        }
    }

    /**
     * 转发请求到目标 URL，并将后端响应写回当前客户端连接。
     * 异步实现：用 sendAsync 发起，不占用调用线程等待上游；
     * 响应体边收边写，不再整包聚成 byte[] 再包 FullHttpResponse。
     *
     * @return 代理结果 Future：最终状态码、上游往返耗时、是否连接失败/超时，方便指标统计
     */
    public CompletableFuture<ProxyResult> forwardAsync(
            ChannelHandlerContext ctx,
            io.netty.handler.codec.http.HttpRequest request,
            String targetUrl) {
        InboundBodyPipe body = request instanceof FullHttpRequest full
                ? InboundBodyPipe.fromFull(full, GatewayDefaults.MAX_REQUEST_BODY_BYTES)
                : InboundBodyPipe.empty(GatewayDefaults.MAX_REQUEST_BODY_BYTES);
        return forwardAsync(ctx, request, targetUrl, body);
    }

    public CompletableFuture<ProxyResult> forwardAsync(
            ChannelHandlerContext ctx,
            io.netty.handler.codec.http.HttpRequest request,
            String targetUrl,
            InboundBodyPipe body) {
        return forwardAsync(ctx, request, targetUrl, body, true);
    }

    public CompletableFuture<ProxyResult> forwardAsync(
            ChannelHandlerContext ctx,
            io.netty.handler.codec.http.HttpRequest request,
            String targetUrl,
            InboundBodyPipe body,
            boolean writeClientError) {
        // netty 模式
        if (nettyClient != null) {
            return nettyClient.forwardAsync(ctx, request, targetUrl, body, writeClientError);
        }
        // jdk 模式
        long startNanos = System.nanoTime();
        inFlight.incrementAndGet();
        return body.collectBytes().thenCompose(bytes -> {
            try {
                java.net.http.HttpRequest proxyRequest = buildProxyRequest(ctx, request, targetUrl, bytes);
                return httpClient
                        .sendAsync(proxyRequest, info -> new StreamingResponseSubscriber(ctx, info, DEFAULT_MAX_RESPONSE_BYTES))
                        .handle((proxyResponse, err) -> {
                            if (err != null) {
                                return handleError(ctx, err, targetUrl, startNanos, writeClientError);
                            }
                            return new ProxyResult(proxyResponse.statusCode(), elapsedMillis(startNanos), false, false);
                        });
            } catch (Exception err) {
                return CompletableFuture.completedFuture(
                        handleError(ctx, err, targetUrl, startNanos, writeClientError));
            }
        }).exceptionally(err -> {
            if (body.isAborted()) {
                int code = body.overflowed() ? 413 : 499;
                return new ProxyResult(code, elapsedMillis(startNanos), false, false);
            }
            return handleError(ctx, err, targetUrl, startNanos, writeClientError);
        }).whenComplete((ignored, err) -> inFlight.decrementAndGet());
    }

    /** 第一枪压了错包时，换台失败或不换台，再补写给客户端。头已写出就不动。 */
    public void writeDeferredError(ChannelHandlerContext ctx, ProxyResult result) {
        if (ctx == null || result == null) {
            return;
        }
        if (Boolean.TRUE.equals(ctx.channel().attr(RESPONSE_STARTED).get())) {
            return;
        }
        if (result.timeout()) {
            writeProxyError(ctx, HttpResponseStatus.GATEWAY_TIMEOUT, "后端请求超时");
            return;
        }
        writeProxyError(
                ctx,
                HttpResponseStatus.BAD_GATEWAY,
                result.connectFail() ? "后端连接失败" : "后端响应异常");
    }

    /** 统一错误分类：把异常解包后按类型回写对应错误响应，并返回可统计的 ProxyResult。 */
    private ProxyResult handleError(
            ChannelHandlerContext ctx,
            Throwable err,
            String targetUrl,
            long startNanos) {
        return handleError(ctx, err, targetUrl, startNanos, true);
    }

    private ProxyResult handleError(
            ChannelHandlerContext ctx,
            Throwable err,
            String targetUrl,
            long startNanos,
            boolean writeClientError) {
        Throwable cause = unwrap(err);
        String safeTarget = redactTargetUrl(targetUrl);
        // 响应头已写出时不能再塞 JSON 错包，只能关连接，避免半包后再写一个完整响应。
        if (clearResponseStarted(ctx)) {
            if (ctx.channel().isActive()) {
                ctx.close();
            }
            return classifyWithoutWrite(cause, startNanos);
        }
        if (!writeClientError) {
            return classifyWithoutWrite(cause, startNanos);
        }
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

    /** 头已发出后的失败：只归类指标，不再写 body。 */
    private static ProxyResult classifyWithoutWrite(Throwable cause, long startNanos) {
        if (cause instanceof HttpTimeoutException) {
            return new ProxyResult(HttpResponseStatus.GATEWAY_TIMEOUT.code(), elapsedMillis(startNanos), false, true);
        }
        if (cause instanceof ResponseTooLargeException) {
            return new ProxyResult(HttpResponseStatus.BAD_GATEWAY.code(), elapsedMillis(startNanos), false, false);
        }
        if (cause instanceof ConnectException || cause instanceof InterruptedException
                || cause instanceof IllegalArgumentException) {
            return new ProxyResult(HttpResponseStatus.BAD_GATEWAY.code(), elapsedMillis(startNanos), true, false);
        }
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
    private java.net.http.HttpRequest buildProxyRequest(
            ChannelHandlerContext ctx,
            io.netty.handler.codec.http.HttpRequest request,
            String targetUrl,
            byte[] body) {
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(Duration.ofMillis(requestTimeoutMillis.get()))
                .version(HttpClient.Version.HTTP_1_1);

        copyRequestHeaders(request, builder);
        addForwardedHeaders(ctx, request, builder);
        // JDK 出站仍要整段 body；Netty 路径不走这里。
        return builder.method(request.method().name(), bodyPublisher(body)).build();
    }

    private static java.net.http.HttpRequest.BodyPublisher bodyPublisher(byte[] body) {
        if (body == null || body.length == 0) {
            return java.net.http.HttpRequest.BodyPublishers.noBody();
        }
        return java.net.http.HttpRequest.BodyPublishers.ofByteArray(body);
    }

    /**
     * 复制客户端业务 Header。
     * Authorization、Cookie、Content-Type 等会保留；
     * Host、Content-Length、X-Forwarded-* 等由网关自己处理，不原样透传。
     */
    private void copyRequestHeaders(
            io.netty.handler.codec.http.HttpRequest request,
            java.net.http.HttpRequest.Builder builder) {
        for (Map.Entry<String, String> header : request.headers()) {
            if (HopByHopHeaders.isHopByHop(header.getKey()) || HopByHopHeaders.isManagedForward(header.getKey())) {
                continue;
            }
            builder.header(header.getKey(), header.getValue());
        }
    }

    /** 补充网关标准转发头，方便后端识别原始来源。 */
    private void addForwardedHeaders(
            ChannelHandlerContext ctx,
            io.netty.handler.codec.http.HttpRequest request,
            java.net.http.HttpRequest.Builder builder) {
        String requestId = request.headers().get(HttpConstants.REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = RequestIds.next();
        }

        builder.setHeader(HttpConstants.REQUEST_ID_HEADER, requestId);
        builder.setHeader(HttpConstants.FORWARDED_PROTO_HEADER, HttpConstants.SCHEME_HTTP);

        String host = request.headers().get(HttpHeaderNames.HOST);
        if (host != null && !host.isBlank()) {
            builder.setHeader(HttpConstants.FORWARDED_HOST_HEADER, host);
        }

        String clientIp = clientIp(ctx);
        if (clientIp != null && !clientIp.isBlank()) {
            // 未配置可信代理链时丢弃客户端自带 XFF，只写直连地址，避免后端信任伪造首段。
            builder.setHeader(HttpConstants.FORWARDED_FOR_HEADER, clientIp);
        }
    }

    /** 写出上游状态行与头；Content-Length 单独透传，其余 hop-by-hop 丢掉。 */
    static void writeResponseHeaders(ChannelHandlerContext ctx, ResponseInfo info) {
        HttpResponse response = new DefaultHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(info.statusCode()));

        info.headers().map().forEach((name, values) -> {
            if (!HopByHopHeaders.isHopByHop(name)) {
                response.headers().set(name, values);
            }
        });

        // 上游有明确长度就带上，编码器走定长；没有则后续 HttpContent 由编码器做 chunked。
        List<String> contentLength = info.headers().allValues("content-length");
        if (!contentLength.isEmpty()) {
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, contentLength.get(0));
        }

        ctx.channel().attr(RESPONSE_STARTED).set(Boolean.TRUE);
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
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpConstants.MEDIA_TYPE_JSON_UTF8);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        ctx.writeAndFlush(response);
    }

    private String clientIp(ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress address) {
            return address.getAddress().getHostAddress();
        }
        return null;
    }

    private static boolean clearResponseStarted(ChannelHandlerContext ctx) {
        Boolean started = ctx.channel().attr(RESPONSE_STARTED).getAndSet(null);
        return Boolean.TRUE.equals(started);
    }

    private static void runOnEventLoop(ChannelHandlerContext ctx, Runnable task) {
        if (ctx.channel().eventLoop().inEventLoop()) {
            task.run();
        } else {
            ctx.channel().eventLoop().execute(task);
        }
    }

    private static void releaseAll(List<ByteBuf> buffers) {
        for (ByteBuf buf : buffers) {
            buf.release();
        }
    }

    /**
     * 边收上游 body 边写给下游。
     * onNext 返回前必须拷走 ByteBuffer（JDK 约定可复用）；写 Netty 必须回到 EventLoop。
     */
    static final class StreamingResponseSubscriber implements BodySubscriber<Void> {

        private final ChannelHandlerContext ctx;
        private final ResponseInfo info;
        private final int maxBytes;
        private final CompletableFuture<Void> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private int received;
        private final AtomicBoolean failed = new AtomicBoolean();

        StreamingResponseSubscriber(ChannelHandlerContext ctx, ResponseInfo info, int maxBytes) {
            this.ctx = ctx;
            this.info = info;
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<Void> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            runOnEventLoop(ctx, () -> {
                if (failed.get() || !ctx.channel().isActive()) {
                    subscription.cancel();
                    body.completeExceptionally(new IllegalStateException("channel inactive"));
                    return;
                }
                try {
                    writeResponseHeaders(ctx, info);
                    subscription.request(1);
                } catch (Throwable ex) {
                    subscription.cancel();
                    body.completeExceptionally(ex);
                }
            });
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (failed.get()) {
                return;
            }
            List<ByteBuf> chunks = new ArrayList<>(buffers.size());
            try {
                for (ByteBuffer buffer : buffers) {
                    int length = buffer.remaining();
                    if (length > maxBytes - received) {
                        releaseAll(chunks);
                        failTooLarge();
                        return;
                    }
                    // JDK 约定 onNext 返回后 ByteBuffer 可复用，必须先拷进 Netty。
                    chunks.add(Unpooled.copiedBuffer(buffer));
                    received += length;
                }
            } catch (Throwable ex) {
                releaseAll(chunks);
                fail(ex);
                return;
            }

            runOnEventLoop(ctx, () -> {
                if (failed.get() || !ctx.channel().isActive()) {
                    releaseAll(chunks);
                    return;
                }
                try {
                    for (ByteBuf chunk : chunks) {
                        ctx.write(new DefaultHttpContent(chunk));
                    }
                    ctx.flush();
                    subscription.request(1);
                } catch (Throwable ex) {
                    releaseAll(chunks);
                    fail(ex);
                }
            });
        }

        @Override
        public void onError(Throwable throwable) {
            fail(throwable);
        }

        @Override
        public void onComplete() {
            runOnEventLoop(ctx, () -> {
                if (failed.get()) {
                    return;
                }
                try {
                    ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                    clearResponseStarted(ctx);
                    body.complete(null);
                } catch (Throwable ex) {
                    fail(ex);
                }
            });
        }

        private void failTooLarge() {
            fail(new ResponseTooLargeException(maxBytes));
        }

        private void fail(Throwable ex) {
            if (!failed.compareAndSet(false, true)) {
                return;
            }
            if (subscription != null) {
                subscription.cancel();
            }
            runOnEventLoop(ctx, () -> {
                // 头已写出则由外层 handleError 关连接；这里只完成 Future。
                body.completeExceptionally(ex);
            });
        }
    }

    /** 超限异常；单测也可直接构造 StreamingResponseSubscriber 验证。 */
    static final class ResponseTooLargeException extends RuntimeException {
        ResponseTooLargeException(int maxBytes) {
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
            // 非法 URL 仍不能把 query 或 userInfo 原样写入日志，退化为去掉 query 的原字符串。
            int query = targetUrl.indexOf('?');
            return query < 0 ? targetUrl : targetUrl.substring(0, query);
        }
    }
}
