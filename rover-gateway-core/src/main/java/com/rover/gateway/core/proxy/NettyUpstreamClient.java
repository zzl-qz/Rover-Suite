package com.rover.gateway.core.proxy;

import com.rover.common.constants.HttpConstants;
import com.rover.common.json.JsonCodec;
import com.rover.gateway.core.config.GatewayDefaults;
import com.rover.gateway.core.server.IoTransport;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * Netty 出站：和入站共用 EventLoop，响应 ByteBuf 直接往下写，不再绕 JDK HttpClient。
 * JDK 那条还在 HttpProxyClient 里，outbound=jdk 可切回去。
 */
@Slf4j
final class NettyUpstreamClient {

    private static final AttributeKey<Exchange> EXCHANGE = AttributeKey.valueOf("rover.netty.exchange");
    private static final AttributeKey<Boolean> RESPONSE_STARTED =
            AttributeKey.valueOf("rover.proxy.responseStarted");
    private static final int MAX_CONNECTIONS_PER_LOOP = 64;
    private static final int MAX_PENDING_ACQUIRES = 256;

    private final int connectTimeoutMillis;
    private final AtomicLong requestTimeoutMillis;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ConcurrentHashMap<PoolKey, FixedChannelPool> pools = new ConcurrentHashMap<>();
    private volatile EventLoopGroup fallbackGroup;

    NettyUpstreamClient(int connectTimeoutMillis, int requestTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.requestTimeoutMillis = new AtomicLong(requestTimeoutMillis);
    }

    int getInFlightCount() {
        return inFlight.get();
    }

    void setRequestTimeoutMillis(long timeoutMillis) {
        this.requestTimeoutMillis.set(timeoutMillis);
    }

    CompletableFuture<HttpProxyClient.ProxyResult> forwardAsync(
            ChannelHandlerContext clientCtx,
            FullHttpRequest request,
            String targetUrl) {
        return forwardAsync(
                clientCtx,
                request,
                targetUrl,
                InboundBodyPipe.fromFull(request, GatewayDefaults.MAX_REQUEST_BODY_BYTES));
    }

    CompletableFuture<HttpProxyClient.ProxyResult> forwardAsync(
            ChannelHandlerContext clientCtx,
            HttpRequest request,
            String targetUrl,
            InboundBodyPipe body) {
        return forwardAsync(clientCtx, request, targetUrl, body, true);
    }

    // netty模式 发送请求
    CompletableFuture<HttpProxyClient.ProxyResult> forwardAsync(
            ChannelHandlerContext clientCtx,
            HttpRequest request,
            String targetUrl,
            InboundBodyPipe body,
            boolean writeClientError) {
        long startNanos = System.nanoTime();

        // 将targetUrl解析成URI，并检查是否有host，是否是http
        URI uri;
        try {
            uri = URI.create(targetUrl);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("missing host");
            }
            if (uri.getScheme() != null && !"http".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException(
                        "netty 出站只支持 http 上游；https 请设 rover.gateway.proxy.outbound=jdk");
            }
        } catch (Exception err) {
            if (writeClientError) {
                body.abort();
            }
            return CompletableFuture.completedFuture(
                    handleError(clientCtx, err, targetUrl, startNanos, writeClientError));
        }

        // 拿到ip 端口
        int port = uri.getPort() > 0 ? uri.getPort() : 80;
        EventLoop loop = outboundLoop(clientCtx);
        FixedChannelPool pool = pools.computeIfAbsent(
                new PoolKey(loop, uri.getHost(), port),
                key -> newPool(key));

        inFlight.incrementAndGet();
        CompletableFuture<HttpProxyClient.ProxyResult> result = new CompletableFuture<>();
        try {
            Future<Channel> acquire = pool.acquire();
            acquire.addListener(future -> {
                if (!future.isSuccess()) {
                    if (writeClientError) {
                        body.abort();
                    }
                    finish(result, handleError(
                            clientCtx, future.cause(), targetUrl, startNanos, writeClientError));
                    return;
                }
                Channel upstream = acquire.getNow();
                Exchange exchange = new Exchange(clientCtx, pool, upstream, result, startNanos, targetUrl, body);
                upstream.attr(EXCHANGE).set(exchange);
                exchange.timeout = loop.schedule(
                        () -> fail(exchange, new TimeoutException("upstream request timeout")),
                        requestTimeoutMillis.get(),
                        TimeUnit.MILLISECONDS);
                try {
                    startOutbound(exchange, request, uri, port, body);
                } catch (Exception err) {
                    fail(exchange, err);
                }
            });
        } catch (Exception err) {
            if (writeClientError) {
                body.abort();
            }
            finish(result, handleError(clientCtx, err, targetUrl, startNanos, writeClientError));
        }
        return result;
    }

    /**
     * GET/空 body 必须一帧 Full 发出去。
     * 头先写、Last 还在 biz 线程路上时，上游已经按 Content-Length:0 回了，
     * 出站 codec 还以为请求没写完，连接池下一发就会 502。
     */
    private void startOutbound(
            Exchange exchange,
            HttpRequest request,
            URI uri,
            int port,
            InboundBodyPipe body) {
        HttpRequest outbound = buildOutboundHeaders(exchange.clientCtx, request, uri, port);
        boolean emptyBody = !HttpUtil.isTransferEncodingChunked(outbound)
                && outbound.headers().getInt(HttpHeaderNames.CONTENT_LENGTH, -1) == 0;
        ChannelFuture write;
        if (emptyBody) {
            DefaultFullHttpRequest full = new DefaultFullHttpRequest(
                    outbound.protocolVersion(),
                    outbound.method(),
                    outbound.uri(),
                    Unpooled.EMPTY_BUFFER);
            full.headers().set(outbound.headers());
            write = exchange.upstream.writeAndFlush(full);
            body.attach(HttpContent::release);
        } else {
            write = exchange.upstream.writeAndFlush(outbound);
            body.attach(chunk -> writeInboundChunk(exchange, chunk));
        }
        write.addListener(future -> {
            if (!future.isSuccess()) {
                fail(exchange, future.cause());
            }
        });
    }

    private void writeInboundChunk(Exchange exchange, HttpContent chunk) {
        if (exchange.done.get()) {
            chunk.release();
            return;
        }
        try {
            Channel upstream = exchange.upstream;
            boolean last = chunk instanceof LastHttpContent;
            ByteBuf data = chunk.content();
            if (data.isReadable()) {
                upstream.write(new DefaultHttpContent(data.retain()));
            }
            if (last) {
                upstream.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(write -> {
                    if (!write.isSuccess()) {
                        fail(exchange, write.cause());
                    }
                });
            }
        } finally {
            chunk.release();
        }
    }

    void close() {
        for (FixedChannelPool pool : pools.values()) {
            pool.close();
        }
        pools.clear();
        EventLoopGroup group = fallbackGroup;
        if (group != null) {
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS);
            fallbackGroup = null;
        }
    }

    private EventLoop outboundLoop(ChannelHandlerContext clientCtx) {
        EventLoop loop = clientCtx.channel().eventLoop();
        if (IoTransport.current().sameFamily(loop)) {
            return loop;
        }
        return fallbackGroup().next();
    }

    private synchronized EventLoopGroup fallbackGroup() {
        if (fallbackGroup == null) {
            fallbackGroup = IoTransport.current().newGroup(1);
        }
        return fallbackGroup;
    }

    private FixedChannelPool newPool(PoolKey key) {
        Bootstrap bootstrap = new Bootstrap()
                .group(key.loop)
                .channel(IoTransport.current().clientChannelClass())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .remoteAddress(key.host, key.port);
        return new FixedChannelPool(
                bootstrap,
                new PoolHandler(),
                ChannelHealthChecker.ACTIVE,
                FixedChannelPool.AcquireTimeoutAction.FAIL,
                connectTimeoutMillis,
                MAX_CONNECTIONS_PER_LOOP,
                MAX_PENDING_ACQUIRES);
    }

    private DefaultHttpRequest buildOutboundHeaders(
            ChannelHandlerContext clientCtx,
            HttpRequest inbound,
            URI uri,
            int port) {
        DefaultHttpRequest outbound = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1,
                inbound.method(),
                requestUri(uri));
        for (Map.Entry<String, String> header : inbound.headers()) {
            if (HopByHopHeaders.isHopByHop(header.getKey()) || HopByHopHeaders.isManagedForward(header.getKey())) {
                continue;
            }
            outbound.headers().add(header.getKey(), header.getValue());
        }
        addForwardedHeaders(clientCtx, inbound, outbound);
        outbound.headers().set(HttpHeaderNames.HOST, hostHeader(uri.getHost(), port));
        String contentLength = inbound.headers().get(HttpHeaderNames.CONTENT_LENGTH);
        if (contentLength != null) {
            outbound.headers().set(HttpHeaderNames.CONTENT_LENGTH, contentLength);
        } else if (inbound.headers().contains(HttpHeaderNames.TRANSFER_ENCODING)) {
            HttpUtil.setTransferEncodingChunked(outbound, true);
        } else {
            outbound.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        }
        HttpUtil.setKeepAlive(outbound, true);
        return outbound;
    }

    private static String requestUri(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (uri.getRawQuery() != null) {
            return path + "?" + uri.getRawQuery();
        }
        return path;
    }

    private static String hostHeader(String host, int port) {
        return port == 80 ? host : host + ":" + port;
    }

    private static void addForwardedHeaders(
            ChannelHandlerContext clientCtx,
            HttpRequest inbound,
            HttpRequest outbound) {
        String requestId = inbound.headers().get(HttpConstants.REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = RequestIds.next();
        }
        outbound.headers().set(HttpConstants.REQUEST_ID_HEADER, requestId);
        outbound.headers().set(HttpConstants.FORWARDED_PROTO_HEADER, HttpConstants.SCHEME_HTTP);
        String host = inbound.headers().get(HttpHeaderNames.HOST);
        if (host != null && !host.isBlank()) {
            outbound.headers().set(HttpConstants.FORWARDED_HOST_HEADER, host);
        }
        if (clientCtx.channel().remoteAddress() instanceof InetSocketAddress address) {
            outbound.headers().set(HttpConstants.FORWARDED_FOR_HEADER, address.getAddress().getHostAddress());
        }
    }

    private void fail(Exchange exchange, Throwable err) {
        if (!exchange.done.compareAndSet(false, true)) {
            return;
        }
        cancelTimeout(exchange);
        exchange.body.abort();
        HttpProxyClient.ProxyResult result = handleError(
                exchange.clientCtx, err, exchange.targetUrl, exchange.startNanos, true);
        release(exchange, true);
        finish(exchange.result, result);
    }

    private void succeed(Exchange exchange, int statusCode, boolean keepAlive) {
        if (!exchange.done.compareAndSet(false, true)) {
            return;
        }
        cancelTimeout(exchange);
        clearStarted(exchange.clientCtx);
        release(exchange, !keepAlive);
        finish(exchange.result, new HttpProxyClient.ProxyResult(
                statusCode, elapsedMillis(exchange.startNanos), false, false));
    }

    private void release(Exchange exchange, boolean closeChannel) {
        Channel upstream = exchange.upstream;
        upstream.attr(EXCHANGE).set(null);
        if (closeChannel && upstream.isActive()) {
            upstream.close();
        }
        ChannelPool pool = exchange.pool;
        if (pool != null && upstream != null) {
            pool.release(upstream);
        }
    }

    private void finish(CompletableFuture<HttpProxyClient.ProxyResult> result, HttpProxyClient.ProxyResult value) {
        inFlight.updateAndGet(v -> Math.max(0, v - 1));
        result.complete(value);
    }

    private static void cancelTimeout(Exchange exchange) {
        ScheduledFuture<?> timeout = exchange.timeout;
        if (timeout != null) {
            timeout.cancel(false);
        }
    }

    private HttpProxyClient.ProxyResult handleError(
            ChannelHandlerContext ctx,
            Throwable err,
            String targetUrl,
            long startNanos,
            boolean writeClientError) {
        Throwable cause = unwrap(err);
        String safeTarget = HttpProxyClient.redactTargetUrl(targetUrl);
        if (clearStarted(ctx)) {
            if (ctx.channel().isActive()) {
                ctx.close();
            }
            return classify(cause, startNanos);
        }
        if (!writeClientError) {
            return classify(cause, startNanos);
        }
        if (cause instanceof TimeoutException) {
            log.warn("Proxy request timeout, targetUrl={}", safeTarget);
            writeProxyError(ctx, HttpResponseStatus.GATEWAY_TIMEOUT, "后端请求超时");
            return new HttpProxyClient.ProxyResult(
                    HttpResponseStatus.GATEWAY_TIMEOUT.code(), elapsedMillis(startNanos), false, true);
        }
        if (cause instanceof HttpProxyClient.ResponseTooLargeException) {
            log.warn("Proxy response too large, targetUrl={}", safeTarget);
            writeProxyError(ctx, HttpResponseStatus.BAD_GATEWAY, "后端响应体超过网关上限");
            return new HttpProxyClient.ProxyResult(
                    HttpResponseStatus.BAD_GATEWAY.code(), elapsedMillis(startNanos), false, false);
        }
        if (cause instanceof ConnectException
                || cause instanceof IllegalArgumentException
                || isConnectTimeout(cause)) {
            log.warn("Proxy target connection error, targetUrl={}", safeTarget, cause);
            writeProxyError(ctx, HttpResponseStatus.BAD_GATEWAY, "后端连接失败");
            return new HttpProxyClient.ProxyResult(
                    HttpResponseStatus.BAD_GATEWAY.code(), elapsedMillis(startNanos), true, false);
        }
        log.warn("Proxy request IO error, targetUrl={}", safeTarget, cause);
        writeProxyError(ctx, HttpResponseStatus.BAD_GATEWAY, "后端响应异常");
        return new HttpProxyClient.ProxyResult(
                HttpResponseStatus.BAD_GATEWAY.code(), elapsedMillis(startNanos), true, false);
    }

    private static boolean isConnectTimeout(Throwable cause) {
        return cause != null && cause.getClass().getName().contains("ConnectTimeout");
    }

    private static HttpProxyClient.ProxyResult classify(Throwable cause, long startNanos) {
        if (cause instanceof TimeoutException) {
            return new HttpProxyClient.ProxyResult(
                    HttpResponseStatus.GATEWAY_TIMEOUT.code(), elapsedMillis(startNanos), false, true);
        }
        if (cause instanceof ConnectException || isConnectTimeout(cause) || cause instanceof IllegalArgumentException) {
            return new HttpProxyClient.ProxyResult(
                    HttpResponseStatus.BAD_GATEWAY.code(), elapsedMillis(startNanos), true, false);
        }
        return new HttpProxyClient.ProxyResult(
                HttpResponseStatus.BAD_GATEWAY.code(), elapsedMillis(startNanos), true, false);
    }

    private static Throwable unwrap(Throwable err) {
        Throwable cause = err;
        while (cause.getCause() != null
                && (cause instanceof io.netty.channel.ConnectTimeoutException
                || cause.getClass().getName().contains("Completion"))) {
            cause = cause.getCause();
        }
        return cause == null ? err : cause;
    }

    private static void writeProxyError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
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
        writeOn(ctx, response, true);
    }

    static void writeResponseHeaders(ChannelHandlerContext ctx, HttpResponse upstream) {
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, upstream.status());
        for (Map.Entry<String, String> header : upstream.headers()) {
            if (!HopByHopHeaders.isHopByHop(header.getKey())) {
                response.headers().add(header.getKey(), header.getValue());
            }
        }
        String contentLength = upstream.headers().get(HttpHeaderNames.CONTENT_LENGTH);
        if (contentLength != null) {
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, contentLength);
        }
        ctx.channel().attr(RESPONSE_STARTED).set(Boolean.TRUE);
        // 头先入队，等 body / LastHttpContent 再 flush，小包少一次 syscall。
        writeOn(ctx, response, false);
    }

    static void writeOn(ChannelHandlerContext ctx, Object msg, boolean flush) {
        EventLoop loop = ctx.channel().eventLoop();
        if (loop.inEventLoop()) {
            if (flush) {
                ctx.writeAndFlush(msg);
            } else {
                ctx.write(msg);
            }
            return;
        }
        loop.execute(() -> {
            if (flush) {
                ctx.writeAndFlush(msg);
            } else {
                ctx.write(msg);
            }
        });
    }

    private static boolean clearStarted(ChannelHandlerContext ctx) {
        Boolean started = ctx.channel().attr(RESPONSE_STARTED).getAndSet(null);
        return Boolean.TRUE.equals(started);
    }

    private static long elapsedMillis(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private final class PoolHandler implements ChannelPoolHandler {
        @Override
        public void channelCreated(Channel ch) {
            ch.pipeline().addLast(new HttpClientCodec());
            ch.pipeline().addLast(new UpstreamResponseHandler());
        }

        @Override
        public void channelAcquired(Channel ch) {
        }

        @Override
        public void channelReleased(Channel ch) {
            ch.attr(EXCHANGE).set(null);
        }
    }

    private final class UpstreamResponseHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Exchange exchange = ctx.channel().attr(EXCHANGE).get();
            if (exchange == null) {
                io.netty.util.ReferenceCountUtil.release(msg);
                return;
            }
            try {
                if (msg instanceof HttpResponse response) {
                    exchange.statusCode = response.status().code();
                    exchange.keepAlive = HttpUtil.isKeepAlive(response);
                    writeResponseHeaders(exchange.clientCtx, response);
                    return;
                }
                if (msg instanceof HttpContent content) {
                    int size = content.content().readableBytes();
                    if (exchange.received + size > GatewayDefaults.MAX_RESPONSE_BODY_BYTES) {
                        fail(exchange, new HttpProxyClient.ResponseTooLargeException(
                                GatewayDefaults.MAX_RESPONSE_BODY_BYTES));
                        return;
                    }
                    exchange.received += size;
                    boolean last = msg instanceof LastHttpContent;
                    if (size > 0) {
                        ByteBuf chunk = content.content().retain();
                        writeOn(exchange.clientCtx, new DefaultHttpContent(chunk), last);
                    }
                    if (last) {
                        writeOn(exchange.clientCtx, LastHttpContent.EMPTY_LAST_CONTENT, true);
                        succeed(exchange, exchange.statusCode, exchange.keepAlive);
                    }
                    return;
                }
            } finally {
                io.netty.util.ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Exchange exchange = ctx.channel().attr(EXCHANGE).get();
            if (exchange != null) {
                fail(exchange, cause);
            } else {
                ctx.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Exchange exchange = ctx.channel().attr(EXCHANGE).get();
            if (exchange != null && !exchange.done.get()) {
                fail(exchange, new ConnectException("upstream connection closed"));
            }
        }
    }

    private static final class Exchange {
        final ChannelHandlerContext clientCtx;
        final ChannelPool pool;
        final Channel upstream;
        final CompletableFuture<HttpProxyClient.ProxyResult> result;
        final long startNanos;
        final String targetUrl;
        final InboundBodyPipe body;
        final AtomicBoolean done = new AtomicBoolean();
        volatile ScheduledFuture<?> timeout;
        volatile int statusCode = 502;
        volatile boolean keepAlive = true;
        int received;

        Exchange(
                ChannelHandlerContext clientCtx,
                ChannelPool pool,
                Channel upstream,
                CompletableFuture<HttpProxyClient.ProxyResult> result,
                long startNanos,
                String targetUrl,
                InboundBodyPipe body) {
            this.clientCtx = clientCtx;
            this.pool = pool;
            this.upstream = upstream;
            this.result = result;
            this.startNanos = startNanos;
            this.targetUrl = targetUrl;
            this.body = body;
        }
    }

    private record PoolKey(EventLoop loop, String host, int port) {
    }
}
