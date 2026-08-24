package com.rover.gateway.core.server;

import com.rover.common.constants.HttpConstants;
import com.rover.gateway.core.filter.DefaultFilterChain;
import com.rover.gateway.core.filter.GatewayRequestContext;
import com.rover.gateway.core.filter.GatewayContextKeys;
import com.rover.gateway.core.manage.GatewayManageApi;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.proxy.HttpProxyClient;
import com.rover.gateway.core.runtime.GatewayRuntime;
import com.rover.gateway.core.trace.RequestTrace;
import com.rover.gateway.core.trace.TraceSettings;
import com.rover.gateway.core.trace.TracePhase;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:58:00
 * Description: Netty 入站 Handler：管理口短路，其余请求走过滤器链
 */
@Slf4j
public class GatewayHttpServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    /** 网关运行时可变状态，含当前过滤器链和路由表。 */
    private final GatewayRuntime runtime;

    /** 同口管理 API 处理器。 */
    private final GatewayManageApi manageApi;

    /** 构造：绑定网关运行时并初始化同口管理 API。 */
    public GatewayHttpServerHandler(GatewayRuntime runtime) {
        this(runtime, true);
    }

    /** 构造：Admin 关闭时不创建管理 API，业务请求不受影响。 */
    public GatewayHttpServerHandler(GatewayRuntime runtime, boolean adminEnabled) {
        this.runtime = runtime;
        this.manageApi = adminEnabled ? new GatewayManageApi(runtime) : null;
    }

    /** 收到完整 HTTP 请求的入口：管理口短路，业务请求走过滤器链。 */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        // 业务路由和上游拼接必须保留原始转义，避免 %20/%2F/中文被提前解码后生成非法 URI。
        String requestPath = new QueryStringDecoder(request.uri()).rawPath();
        // 管理口不进业务过滤器链
        if (requestPath.startsWith(GatewayManageApi.PREFIX)) {
            if (manageApi != null) {
                manageApi.handle(ctx, request, requestPath);
            } else {
                writeText(ctx, HttpResponseStatus.NOT_FOUND, "Gateway Admin API is disabled");
            }
            return;
        }
        // 准入控制：超过在途上限时快速 503，避免内存/上游连接被无限堆积
        if (!runtime.tryAcquireProcessingPermit()) {
            log.warn("Gateway overloaded, reject request path={}", requestPath);
            writeText(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "Gateway is overloaded, please retry later");
            return;
        }

        runtime.getMetricsRegistry().requestStarted();
        GatewayRequestContext context = new GatewayRequestContext(ctx, request, requestPath);
        // 生成/透传 traceId，供后端日志关联（转发时随请求头原样透传）
        ensureTraceId(request, context);
        context.markPhase(TracePhase.RECEIVE.phaseName(), System.nanoTime() - context.getStartNanos());

        // 异步推进过滤器链，收尾统一放在完成回调里，不阻塞业务线程等待上游。
        CompletableFuture<Void> chainFuture;
        try {
            chainFuture = new DefaultFilterChain(runtime.currentFilters()).doFilter(context);
        } catch (Exception err) {
            chainFuture = CompletableFuture.failedFuture(err);
        }
        chainFuture.whenComplete((ignored, err) -> {
            try {
                if (err != null) {
                    log.warn("Gateway filter chain error, requestPath={}", requestPath, err);
                    if (!context.isCompleted()) {
                        context.writeText(
                                HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                "Gateway filter chain error: " + err.getMessage());
                    }
                }
            } finally {
                runtime.getMetricsRegistry().requestFinished();
                recordTraceIfNeeded(context);
                runtime.releaseProcessingPermit();
            }
        });
    }

    /** 请求头已有 traceId 则沿用，否则生成；同时写入上下文属性。 */
    private void ensureTraceId(FullHttpRequest request, GatewayRequestContext context) {
        String traceId = request.headers().get(HttpConstants.TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            request.headers().set(HttpConstants.TRACE_ID_HEADER, traceId);
        }
        context.setAttribute(GatewayContextKeys.TRACE_ID, traceId);
    }

    /** 慢请求或采样命中时记录链路时间线（写回耗时按剩余时间兜底，保证各阶段之和等于总耗时）。 */
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

    /** 连接建立：计入活跃连接数。 */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        runtime.getMetricsRegistry().connectionOpened();
        super.channelActive(ctx);
    }

    /** 连接关闭：扣减活跃连接数。 */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        runtime.getMetricsRegistry().connectionClosed();
        super.channelInactive(ctx);
    }

    /** 管道异常：body 过大返回 413，其它异常打日志并关连接。 */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof TooLongFrameException) {
            writeText(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "Request body too large", true);
            return;
        }
        log.warn("Gateway request handling error", cause);
        ctx.close();
    }

    /** 向客户端写纯文本响应。 */
    private void writeText(ChannelHandlerContext ctx, HttpResponseStatus status, String responseBody) {
        writeText(ctx, status, responseBody, false);
    }

    private void writeText(
            ChannelHandlerContext ctx,
            HttpResponseStatus status,
            String responseBody,
            boolean closeConnection) {
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpConstants.MEDIA_TYPE_TEXT_UTF8);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        if (closeConnection) {
            response.headers().set(HttpHeaderNames.CONNECTION, "close");
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
            ctx.writeAndFlush(response);
        }
    }
}
