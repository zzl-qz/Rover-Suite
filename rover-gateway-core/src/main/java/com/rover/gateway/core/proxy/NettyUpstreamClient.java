package com.rover.gateway.core.proxy;

import com.rover.common.constants.HttpConstants;
import com.rover.common.json.JsonCodec;
import com.rover.gateway.core.config.GatewayDefaults;
import com.rover.gateway.core.config.GatewaySystemProperties;
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
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ScheduledFuture;
import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * Netty 出站：和入站共用 EventLoop，响应 ByteBuf 直接往下写，不再绕 JDK HttpClient。
 * JDK 那条还在 HttpProxyClient 里，outbound=jdk 可切回去。
 */
@Slf4j
final class NettyUpstreamClient {

    static final AttributeKey<Exchange> EXCHANGE = AttributeKey.valueOf("rover.netty.exchange");
    private static final AttributeKey<Boolean> RESPONSE_STARTED =
            AttributeKey.valueOf("rover.proxy.responseStarted");
    private final int connectTimeoutMillis;
    private final int maxConnectionsPerEventLoop;
    private final int maxPendingAcquires;
    private final int idleTimeoutSeconds;
    private final AtomicLong requestTimeoutMillis;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ConcurrentHashMap<PoolKey, FixedChannelPool> pools = new ConcurrentHashMap<>();
    private volatile EventLoopGroup fallbackGroup;

    /** 初始化出站连接池的容量、连接超时和请求超时配置。 */
    NettyUpstreamClient(int connectTimeoutMillis, int requestTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.requestTimeoutMillis = new AtomicLong(requestTimeoutMillis);
        this.maxConnectionsPerEventLoop = GatewayDefaults.intPropertyOrDefault(
                GatewaySystemProperties.MAX_CONNECTIONS_PER_EVENT_LOOP,
                GatewayDefaults.MAX_CONNECTIONS_PER_EVENT_LOOP);
        this.maxPendingAcquires = GatewayDefaults.intPropertyOrDefault(
                GatewaySystemProperties.MAX_PENDING_ACQUIRES,
                GatewayDefaults.MAX_PENDING_ACQUIRES);
        this.idleTimeoutSeconds = GatewayDefaults.intPropertyOrDefault(
                GatewaySystemProperties.OUTBOUND_IDLE_TIMEOUT_SECONDS,
                GatewayDefaults.OUTBOUND_IDLE_TIMEOUT_SECONDS);
        log.info("Netty 出站连接池: maxConnectionsPerEventLoop={}, maxPendingAcquires={}, idleTimeoutSeconds={}",
                maxConnectionsPerEventLoop, maxPendingAcquires, idleTimeoutSeconds);
    }

    /** 返回当前正在处理的出站请求数。 */
    int getInFlightCount() {
        return inFlight.get();
    }

    /** 热更新单个上游请求允许占用的最长时间。 */
    void setRequestTimeoutMillis(long timeoutMillis) {
        this.requestTimeoutMillis.set(timeoutMillis);
    }

    /** 兼容完整请求：先把 body 包成管道，再走统一的流式转发。 */
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

    /** 转发已经拆成请求头和 body 管道的入站请求。 */
    CompletableFuture<HttpProxyClient.ProxyResult> forwardAsync(
            ChannelHandlerContext clientCtx,
            HttpRequest request,
            String targetUrl,
            InboundBodyPipe body) {
        return forwardAsync(clientCtx, request, targetUrl, body, true);
    }

    /** 校验目标、借连接并启动一次 Netty 出站交换。 */
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
        // 出站Channel不需要重新分配EventLoop，而是尝试"寄生"在入站Channel已有的EventLoop上
        EventLoop loop = outboundLoop(clientCtx);
        FixedChannelPool pool = pools.computeIfAbsent(
                new PoolKey(loop, uri.getHost(), port),
                key -> newPool(key)); // 第一个到的线程会进行创建这个池子

        inFlight.incrementAndGet();
        CompletableFuture<HttpProxyClient.ProxyResult> result = new CompletableFuture<>();
        AtomicReference<Exchange> exchangeRef = new AtomicReference<>();
        // 413 / 客户端半截不送：入站 abort 立刻拆上游，别干等请求超时
        body.whenAborted(() -> {
            Exchange exchange = exchangeRef.get();
            if (exchange != null) {
                fail(exchange, new IOException("inbound aborted"), false);
            } else {
                finish(result, abortedResult(body, startNanos));
            }
        });
        if (result.isDone()) {
            return result;
        }
        try {
            Future<Channel> acquire = pool.acquire();
            acquire.addListener(future -> {
                if (!future.isSuccess()) {
                    if (result.isDone()) {
                        return;
                    }
                    if (writeClientError) {
                        body.abort();
                    }
                    finish(result, handleError(
                            clientCtx, future.cause(), targetUrl, startNanos, writeClientError));
                    return;
                }
                Channel upstream = acquire.getNow();
                if (result.isDone() || body.isAborted()) {
                    // 入站已经拆了，这条刚借来的上游别发出去
                    upstream.close();
                    pool.release(upstream);
                    return;
                }
                Exchange exchange = new Exchange(clientCtx, pool, upstream, result, startNanos, targetUrl, body);
                upstream.attr(EXCHANGE).set(exchange);
                exchangeRef.set(exchange);
                if (result.isDone() || body.isAborted()) {
                    fail(exchange, new IOException("inbound aborted"), false);
                    return;
                }
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
            if (result.isDone()) {
                return result;
            }
            if (writeClientError) {
                body.abort();
            }
            finish(result, handleError(clientCtx, err, targetUrl, startNanos, writeClientError));
        }
        return result;
    }

    /**
     * 向上游写请求头，并把入站 body 管道接到上游连接。
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
        if (exchange.done.get() || body.isAborted()) {
            return;
        }
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

    /** 将一片入站 body 转交给上游，并在最后一片结束请求。 */
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

    /** 关闭全部连接池，以及按需创建的备用 EventLoop。 */
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

    /** 优先复用入站 Channel 的 EventLoop，不兼容时切到备用组。 */
    private EventLoop outboundLoop(ChannelHandlerContext clientCtx) {
        EventLoop loop = clientCtx.channel().eventLoop();
        if (IoTransport.current().sameFamily(loop)) {
            return loop;
        }
        return fallbackGroup().next();
    }

    /** 懒创建 I/O 模型不兼容时使用的备用 EventLoop 组。 */
    private synchronized EventLoopGroup fallbackGroup() {
        if (fallbackGroup == null) {
            fallbackGroup = IoTransport.current().newGroup(1);
        }
        return fallbackGroup;
    }

    /** 按 EventLoop 和上游地址创建一个可复用的连接池。 */
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
                maxConnectionsPerEventLoop,
                maxPendingAcquires);
    }

    /** 复制可透传请求头，并补齐 Host、长度和转发链路信息。 */
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

    /** 从目标 URL 取出发给上游的 path 和 query。 */
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

    /** 生成上游请求应携带的 Host 头。 */
    private static String hostHeader(String host, int port) {
        return port == 80 ? host : host + ":" + port;
    }

    /** 写入请求 ID 和客户端来源等转发头。 */
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

    /** 按默认策略结束失败交换，并尽量回写错误给客户端。 */
    private void fail(Exchange exchange, Throwable err) {
        fail(exchange, err, true);
    }

    /** 只允许一个失败分支收尾：取消超时、拆连接并完成结果。 */
    private void fail(Exchange exchange, Throwable err, boolean writeClientError) {
        if (!exchange.done.compareAndSet(false, true)) {
            return;
        }
        cancelTimeout(exchange);
        exchange.body.abort();
        HttpProxyClient.ProxyResult result;
        if (!writeClientError) {
            // 413 已经回给客户端了，这里只拆上游，别再叠 502
            if (clearStarted(exchange.clientCtx) && exchange.clientCtx.channel().isActive()) {
                exchange.clientCtx.close();
            }
            result = abortedResult(exchange.body, exchange.startNanos);
        } else {
            result = handleError(
                    exchange.clientCtx, err, exchange.targetUrl, exchange.startNanos, true);
        }
        release(exchange, true);
        finish(exchange.result, result);
    }

    /** 收到上游最后一片响应后，归还连接并完成本次交换。 */
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

    /** 清理连接上的交换状态，并归还或关闭上游连接。 */
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

    /** 完成结果 Future；只有首次完成时才扣减在途计数。 */
    private void finish(CompletableFuture<HttpProxyClient.ProxyResult> result, HttpProxyClient.ProxyResult value) {
        if (result.complete(value)) {
            inFlight.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    /** 入站拆了：超限 413，半截不送按 499。不算上游挂了。 */
    private static HttpProxyClient.ProxyResult abortedResult(InboundBodyPipe body, long startNanos) {
        int code = body != null && body.overflowed() ? 413 : 499;
        return new HttpProxyClient.ProxyResult(code, elapsedMillis(startNanos), false, false);
    }

    /** 在请求已经结束后取消尚未触发的超时任务。 */
    private static void cancelTimeout(Exchange exchange) {
        ScheduledFuture<?> timeout = exchange.timeout;
        if (timeout != null) {
            timeout.cancel(false);
        }
    }

    /** 根据响应是否已开始和异常类型，关闭连接或回写对应错误码。 */
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

    /** 判断异常是否属于连接阶段超时。 */
    private static boolean isConnectTimeout(Throwable cause) {
        return cause != null && cause.getClass().getName().contains("ConnectTimeout");
    }

    /** 将异常归类为供指标和熔断器使用的代理结果。 */
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

    /** 剥掉连接和 Future 包装异常，保留真正的失败原因。 */
    private static Throwable unwrap(Throwable err) {
        Throwable cause = err;
        while (cause.getCause() != null
                && (cause instanceof io.netty.channel.ConnectTimeoutException
                || cause.getClass().getName().contains("Completion"))) {
            cause = cause.getCause();
        }
        return cause == null ? err : cause;
    }

    /** 向客户端写出统一格式的 JSON 代理错误。 */
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

    /** 透传上游响应头，并标记客户端响应已经开始。 */
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

    /** 始终切回客户端 Channel 所属 EventLoop 执行写操作。 */
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

    /** 取走并清除“响应头已写入”标记。 */
    private static boolean clearStarted(ChannelHandlerContext ctx) {
        Boolean started = ctx.channel().attr(RESPONSE_STARTED).getAndSet(null);
        return Boolean.TRUE.equals(started);
    }

    /** 将起始纳秒时间换算为非负的耗时毫秒数。 */
    private static long elapsedMillis(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private final class PoolHandler implements ChannelPoolHandler {
        /** 为新建的上游连接装配编解码、空闲检测和响应处理器。 */
        @Override
        public void channelCreated(Channel ch) {
            ch.pipeline().addLast(new IdleStateHandler(idleTimeoutSeconds, 0, 0, TimeUnit.SECONDS));
            ch.pipeline().addLast(new OutboundIdleCloser());
            ch.pipeline().addLast(new HttpClientCodec());
            ch.pipeline().addLast(new UpstreamResponseHandler());
        }

        /** 连接借出时无需额外初始化，交换状态在开始转发时绑定。 */
        @Override
        public void channelAcquired(Channel ch) {
        }

        /** 连接归还连接池前清掉上一单残留的交换状态。 */
        @Override
        public void channelReleased(Channel ch) {
            ch.attr(EXCHANGE).set(null);
        }
    }

    private final class UpstreamResponseHandler extends ChannelInboundHandlerAdapter {
        /** 流式接收上游响应，并立刻将头和 body 转写给客户端。 */
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
                }
            } finally {
                io.netty.util.ReferenceCountUtil.release(msg);
            }
        }

        /** 将上游 Channel 的 I/O 异常归入当前交换。 */
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Exchange exchange = ctx.channel().attr(EXCHANGE).get();
            if (exchange != null) {
                fail(exchange, cause);
            } else {
                ctx.close();
            }
        }

        /** 上游连接意外关闭时，结束尚未完成的交换。 */
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

        /** 保存一次客户端到上游转发所需的全部状态。 */
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
