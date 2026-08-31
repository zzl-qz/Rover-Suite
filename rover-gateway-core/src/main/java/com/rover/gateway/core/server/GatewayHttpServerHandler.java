package com.rover.gateway.core.server;

import com.rover.common.constants.HttpConstants;
import com.rover.gateway.core.config.GatewayDefaults;
import com.rover.gateway.core.filter.DefaultFilterChain;
import com.rover.gateway.core.filter.GatewayRequestContext;
import com.rover.gateway.core.filter.GatewayContextKeys;
import com.rover.gateway.core.manage.GatewayManageApi;
import com.rover.gateway.core.proxy.HttpProxyClient;
import com.rover.gateway.core.proxy.InboundBodyPipe;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.runtime.GatewayRuntime;
import com.rover.gateway.core.trace.RequestTrace;
import com.rover.gateway.core.trace.TraceSettings;
import com.rover.gateway.core.trace.TracePhase;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:58:00
 * Description: 入站 Handler：头到了就开业务链，body 走管道，不再等 FullHttpRequest
 */
@Slf4j
public class GatewayHttpServerHandler extends ChannelInboundHandlerAdapter {

    /**
     * 一条入站连接同时只跑一个请求。
     * Handler 挂 biz 池时，pipeline 的第二个请求会进另一条业务线程，
     * 两头一起 write 会把 body 拧在一起。
     */
    private static final AttributeKey<Boolean> CHANNEL_BUSY =
            AttributeKey.valueOf("rover.inbound.busy");

    private static final AttributeKey<InboundExchange> INBOUND =
            AttributeKey.valueOf("rover.inbound.exchange");

    /** 429 掉的那一下，后续 chunk 丢掉，别灌进上一个请求的管道。 */
    private static final AttributeKey<Boolean> DRAIN =
            AttributeKey.valueOf("rover.inbound.drain");

    private final GatewayRuntime runtime;
    private final GatewayManageApi manageApi;
    private final int maxBodyBytes;

    public GatewayHttpServerHandler(GatewayRuntime runtime) {
        this(runtime, true);
    }

    public GatewayHttpServerHandler(GatewayRuntime runtime, boolean adminEnabled) {
        this(runtime, adminEnabled, GatewayDefaults.MAX_REQUEST_BODY_BYTES);
    }

    public GatewayHttpServerHandler(GatewayRuntime runtime, boolean adminEnabled, int maxBodyBytes) {
        this.runtime = runtime;
        this.manageApi = adminEnabled ? new GatewayManageApi(runtime) : null;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // head
        if (msg instanceof HttpRequest request) {
            onHeaders(ctx, request);
        }
        // body
        if (msg instanceof HttpContent content) {
            // FullHttpRequest 就单独去除body加上前面的head凑一下
            if (msg instanceof HttpRequest) {
                // FullHttpRequest 同时是 LastHttpContent。管道只拿 body 副本，别把整包 release 掉。
                // 其实这里不拷贝也行，但是后续想用的时候会报错，防御一手
                ByteBuf data = content.content();
                HttpContent copy = data.readableBytes() == 0
                        ? LastHttpContent.EMPTY_LAST_CONTENT
                        : new DefaultLastHttpContent(data.retainedDuplicate());
                onContent(ctx, copy);
                return;
            }
            onContent(ctx, content);
            return;
        }
        ctx.fireChannelRead(msg);
    }

    private void onHeaders(ChannelHandlerContext ctx, HttpRequest request) {
        String requestPath = rawPath(request.uri());
        if (Boolean.TRUE.equals(ctx.channel().attr(DRAIN).get())) {
            ReferenceCountUtil.release(request);
            return;
        }

        // 枪锁失败说明有http请求在了
        if (!tryOccupy(ctx.channel())) {
            log.warn("Gateway reject pipelined request path={}, reason={}",
                    requestPath, HttpConstants.REJECT_CONNECTION_BUSY);
            ctx.channel().attr(DRAIN).set(Boolean.TRUE);
            writeText(
                    ctx,
                    HttpResponseStatus.TOO_MANY_REQUESTS,
                    "Previous request still in progress on this connection",
                    true,
                    HttpConstants.REJECT_CONNECTION_BUSY);
            ReferenceCountUtil.release(request);
            return;
        }

        if (requestPath.startsWith(GatewayManageApi.PREFIX)) {
            InboundExchange exchange = new InboundExchange(requestPath, request, true, maxBodyBytes);
            ctx.channel().attr(INBOUND).set(exchange);
            return;
        }

        if (!runtime.tryAcquireProcessingPermit()) {
            int used = runtime.inflightUsed();
            int max = runtime.maxInflight();
            runtime.getMetricsRegistry().recordReject(HttpConstants.REJECT_INFLIGHT_LIMIT);
            log.warn("Gateway overloaded, reject request path={}, reason={}, inflight={}/{}",
                    requestPath, HttpConstants.REJECT_INFLIGHT_LIMIT, used, max);
            writeText(
                    ctx,
                    HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "Gateway is overloaded, please retry later",
                    false,
                    HttpConstants.REJECT_INFLIGHT_LIMIT);
            releaseOccupy(ctx.channel());
            ctx.channel().attr(DRAIN).set(Boolean.TRUE);
            return;
        }

        runtime.getMetricsRegistry().requestStarted();
        GatewayRequestContext context = new GatewayRequestContext(ctx, request, requestPath, maxBodyBytes);
        boolean traceOn = runtime.getTraceSettings().isEnabled();
        context.setTraceEnabled(traceOn);
        attachTraceId(request, context, traceOn);
        context.markPhase(TracePhase.RECEIVE.phaseName(), System.nanoTime() - context.getStartNanos());

        InboundExchange exchange = new InboundExchange(requestPath, request, false, context);
        ctx.channel().attr(INBOUND).set(exchange);

        boolean handedOff = false;
        try {
            CompletableFuture<Void> chainFuture;
            try {
                chainFuture = new DefaultFilterChain(runtime.currentFilters()).doFilter(context);
            } catch (Exception err) {
                chainFuture = CompletableFuture.failedFuture(err);
            }
            chainFuture.whenComplete((ignored, err) -> finishBusiness(ctx, context, requestPath, err));
            handedOff = true;
        } finally {
            if (!handedOff) {
                context.getBodyPipe().abort();
                clearInbound(ctx);
                releaseOccupy(ctx.channel());
                runtime.releaseProcessingPermit();
            }
        }
    }

    private void onContent(ChannelHandlerContext ctx, HttpContent content) {
        if (Boolean.TRUE.equals(ctx.channel().attr(DRAIN).get())) {
            boolean last = content instanceof LastHttpContent;
            content.release();
            if (last) {
                ctx.channel().attr(DRAIN).set(null);
            }
            return;
        }

        InboundExchange exchange = ctx.channel().attr(INBOUND).get();
        if (exchange == null) {
            content.release();
            return;
        }

        InboundBodyPipe pipe = exchange.body();
        boolean last = content instanceof LastHttpContent;
        boolean ok = pipe.offer(content);
        if (!ok) {
            rejectTooLarge(ctx, exchange);
            return;
        }
        if (last && exchange.manage) {
            finishManage(ctx, exchange);
        }
    }

    private void finishBusiness(
            ChannelHandlerContext ctx,
            GatewayRequestContext context,
            String requestPath,
            Throwable err) {
        try {
            if (err != null) {
                log.warn("Gateway filter chain error, requestPath={}", requestPath, err);
                if (!context.isCompleted()) {
                    context.writeText(
                            HttpResponseStatus.INTERNAL_SERVER_ERROR,
                            "Gateway internal error");
                }
            }
        } finally {
            context.getBodyPipe().abort();
            clearInbound(ctx);
            releaseOccupy(ctx.channel());
            runtime.getMetricsRegistry().requestFinished();
            recordTraceIfNeeded(context);
            runtime.releaseProcessingPermit();
        }
    }

    private void finishManage(ChannelHandlerContext ctx, InboundExchange exchange) {
        try {
            byte[] body = exchange.body().collectBytes().join();
            FullHttpRequest full = toFull(exchange.headers, body);
            try {
                if (manageApi != null) {
                    manageApi.handle(ctx, full, exchange.path);
                } else {
                    writeText(ctx, HttpResponseStatus.NOT_FOUND, "Gateway Admin API is disabled");
                }
            } finally {
                full.release();
            }
        } catch (Exception err) {
            log.warn("Gateway manage API error, path={}", exchange.path, err);
            writeText(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "manage api error");
        } finally {
            clearInbound(ctx);
            releaseOccupy(ctx.channel());
        }
    }

    // body太大的时候直接拒绝（这里就是开启清除操作）
    private void rejectTooLarge(ChannelHandlerContext ctx, InboundExchange exchange) {
        ctx.channel().attr(DRAIN).set(Boolean.TRUE);
        if (exchange.context != null && !exchange.context.isCompleted()) {
            exchange.context.writeText(
                    HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "Request body too large");
        } else {
            writeText(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "Request body too large", true);
        }
    }

    private void clearInbound(ChannelHandlerContext ctx) {
        InboundExchange exchange = ctx.channel().attr(INBOUND).getAndSet(null);
        if (exchange != null) {
            exchange.body().abort();
        }
    }

    private static FullHttpRequest toFull(HttpRequest headers, byte[] body) {
        DefaultFullHttpRequest full = new DefaultFullHttpRequest(
                headers.protocolVersion(),
                headers.method(),
                headers.uri(),
                body.length == 0 ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(body));
        full.headers().set(headers.headers());
        return full;
    }

    /** 业务路由必须留原始转义，别用 QueryStringDecoder 先解码。 */
    static String rawPath(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "/";
        }
        int end = uri.length();
        int query = uri.indexOf('?');
        if (query >= 0) {
            end = query;
        }
        int hash = uri.indexOf('#');
        if (hash >= 0 && hash < end) {
            end = hash;
        }
        String path = uri.substring(0, end);
        return path.isEmpty() ? "/" : path;
    }

    static boolean tryOccupy(Channel channel) {
        return channel.attr(CHANNEL_BUSY).compareAndSet(null, Boolean.TRUE);
    }

    static void releaseOccupy(Channel channel) {
        channel.attr(CHANNEL_BUSY).set(null);
    }

    static void attachTraceId(HttpRequest request, GatewayRequestContext context, boolean enabled) {
        String traceId = request.headers().get(HttpConstants.TRACE_ID_HEADER);
        if (traceId != null && !traceId.isBlank()) {
            context.setAttribute(GatewayContextKeys.TRACE_ID, traceId);
            return;
        }
        if (!enabled) {
            return;
        }
        traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        request.headers().set(HttpConstants.TRACE_ID_HEADER, traceId);
        context.setAttribute(GatewayContextKeys.TRACE_ID, traceId);
    }

    private void recordTraceIfNeeded(GatewayRequestContext context) {
        TraceSettings settings = runtime.getTraceSettings();
        if (!settings.isEnabled()) {
            return;
        }
        long totalNanos = System.nanoTime() - context.getStartNanos();
        long totalCostMs = totalNanos / 1_000_000;
        long writeNanos = Math.max(0, totalNanos - context.phaseCostSumNanos());
        context.markPhase(TracePhase.WRITE.phaseName(), writeNanos);

        boolean slow = totalCostMs >= settings.getSlowThresholdMillis();
        boolean sampled = settings.getSampleRate() > 0
                && ThreadLocalRandom.current().nextDouble() < settings.getSampleRate();
        if (!slow && !sampled) {
            return;
        }

        try {
            String traceId = String.valueOf(context.getAttribute(GatewayContextKeys.TRACE_ID));
            RouteConfig route = context.getRoute();
            RequestTrace trace = new RequestTrace(
                    traceId,
                    context.getRequest().method().name(),
                    context.getRequestPath(),
                    route == null ? null : route.getId(),
                    HttpProxyClient.redactTargetUrl(context.getTargetUrl()),
                    context.getStatusCode() == null ? 500 : context.getStatusCode(),
                    System.currentTimeMillis() - totalCostMs,
                    totalCostMs,
                    slow,
                    context.phaseCostsMillis());
            runtime.getTraceBuffer().append(trace);
        } catch (Exception err) {
            log.warn("Trace record failed, ignore to protect request path", err);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        runtime.getMetricsRegistry().connectionOpened();
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        InboundExchange exchange = ctx.channel().attr(INBOUND).getAndSet(null);
        if (exchange != null) {
            exchange.body().abort();
            releaseOccupy(ctx.channel());
        }
        runtime.getMetricsRegistry().connectionClosed();
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof TooLongFrameException) {
            writeText(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "Request body too large", true);
            return;
        }
        log.warn("Gateway request handling error", cause);
        ctx.close();
    }

    private void writeText(ChannelHandlerContext ctx, HttpResponseStatus status, String responseBody) {
        writeText(ctx, status, responseBody, false, null);
    }

    private void writeText(
            ChannelHandlerContext ctx,
            HttpResponseStatus status,
            String responseBody,
            boolean closeConnection) {
        writeText(ctx, status, responseBody, closeConnection, null);
    }

    private void writeText(
            ChannelHandlerContext ctx,
            HttpResponseStatus status,
            String responseBody,
            boolean closeConnection,
            String rejectReason) {
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpConstants.MEDIA_TYPE_TEXT_UTF8);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        if (rejectReason != null && !rejectReason.isBlank()) {
            response.headers().set(HttpConstants.REJECT_REASON_HEADER, rejectReason);
        }
        if (closeConnection) {
            response.headers().set(HttpHeaderNames.CONNECTION, "close");
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
            ctx.writeAndFlush(response);
        }
    }

    private static final class InboundExchange {
        final String path;
        final HttpRequest headers;
        final boolean manage;
        final GatewayRequestContext context;
        final InboundBodyPipe manageBody;

        InboundExchange(String path, HttpRequest headers, boolean manage, int maxBodyBytes) {
            this.path = path;
            this.headers = headers;
            this.manage = manage;
            this.context = null;
            this.manageBody = new InboundBodyPipe(maxBodyBytes);
        }

        InboundExchange(String path, HttpRequest headers, boolean manage, GatewayRequestContext context) {
            this.path = path;
            this.headers = headers;
            this.manage = manage;
            this.context = context;
            this.manageBody = null;
        }

        InboundBodyPipe body() {
            return context != null ? context.getBodyPipe() : manageBody;
        }
    }
}
