package com.rover.gateway.core.filter;

import com.rover.common.constants.HttpConstants;
import com.rover.common.spi.filter.RequestContext;
import com.rover.gateway.core.config.GatewayDefaults;
import com.rover.gateway.core.proxy.InboundBodyPipe;
import com.rover.gateway.core.route.RouteConfig;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import lombok.Setter;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: RequestContext 的 Gateway 实现：封装 Netty 通道与 HTTP 请求，记录路由/目标 URL/状态码，支持 writeText 短路回写
 */
@Getter
public class GatewayRequestContext implements RequestContext {

    /** Netty 通道上下文，用来把响应写回客户端。 */
    private final ChannelHandlerContext channelContext;

    /** 入站请求行+头。body 走 bodyPipe，不再等 FullHttpRequest。 */
    private final HttpRequest request;

    /** 入站 body 边到边转；Filter 只看头。 */
    private final InboundBodyPipe bodyPipe;

    /** 请求路径，不含 query 参数。 */
    private final String requestPath;

    /** 请求开始处理的纳秒时间，用于统计耗时。 */
    private final long startNanos;

    /** 过滤器之间共享的自定义属性，比如鉴权结果、traceId 等。 */
    private final Map<String, Object> attributes = new HashMap<>();

    /** 链路时间线阶段耗时（纳秒），按标记顺序排列。 */
    private final List<Phase> phases = new ArrayList<>();

    /** false 时 markPhase 空转，热路径不往 phases 里塞。 */
    @Setter
    private boolean traceEnabled = true;

    /**
     * 记录一个处理阶段耗时。
     *
     * @param name     阶段名（receive/route/proxy/write）
     * @param costNanos 该阶段耗时（纳秒）
     */
    public void markPhase(String name, long costNanos) {
        if (!traceEnabled) {
            return;
        }
        if (costNanos < 0) {
            costNanos = 0;
        }
        phases.add(new Phase(name, costNanos));
    }

    /** 所有已标记阶段耗时之和（纳秒），用于计算剩余写回耗时。 */
    public long phaseCostSumNanos() {
        long sum = 0;
        for (Phase phase : phases) {
            sum += phase.costNanos;
        }
        return sum;
    }

    /** 阶段耗时列表（毫秒，供时间线组装）。 */
    public List<com.rover.gateway.core.trace.RequestTrace.Phase> phaseCostsMillis() {
        List<com.rover.gateway.core.trace.RequestTrace.Phase> result = new java.util.ArrayList<>();
        for (Phase phase : phases) {
            result.add(new com.rover.gateway.core.trace.RequestTrace.Phase(
                    phase.name, phase.costNanos / 1_000_000));
        }
        return result;
    }

    /** 单阶段耗时记录。 */
    private static final class Phase {
        final String name;
        final long costNanos;

        Phase(String name, long costNanos) {
            this.name = name;
            this.costNanos = costNanos;
        }
    }

    /** 命中的路由规则，路由匹配后才会有值。 */
    @Setter
    private RouteConfig route;

    /** 最终要转发的后端 URL。 */
    @Setter
    private String targetUrl;

    /** 最终返回给客户端的状态码。 */
    @Setter
    private Integer statusCode;

    /** 命中的上游实例 host:port，未转发时为 null。 */
    @Setter
    private String upstreamHostPort;

    /** 上游往返耗时（毫秒，含连接 + 上游处理，java.net.http 不暴露拆分点）。 */
    @Setter
    private long upstreamCostMillis;

    /** 上游是否连接失败（ConnectException/IOException/目标非法）。 */
    @Setter
    private boolean upstreamConnectFail;

    /** 上游是否触发请求超时。 */
    @Setter
    private boolean upstreamTimeout;

    /** 请求是否已经结束。跨 EventLoop / 业务线程，必须原子，避免两头各写一份响应。 */
    private final AtomicBoolean completed = new AtomicBoolean();

    /** 构造：记录通道、请求与请求路径，并启动耗时计时。 */
    public GatewayRequestContext(
            ChannelHandlerContext channelContext,
            HttpRequest request,
            String requestPath) {
        this(channelContext, request, requestPath, GatewayDefaults.MAX_REQUEST_BODY_BYTES);
    }

    public GatewayRequestContext(
            ChannelHandlerContext channelContext,
            HttpRequest request,
            String requestPath,
            int maxBodyBytes) {
        this.channelContext = channelContext;
        this.request = request;
        this.requestPath = requestPath;
        this.bodyPipe = new InboundBodyPipe(maxBodyBytes);
        this.startNanos = System.nanoTime();
    }

    /** @param key 属性名 @return 属性值，不存在时 null */
    @Override
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    /** 读取请求头，Netty Headers 已经提供大小写不敏感匹配。 */
    @Override
    public String requestHeader(String name) {
        return name == null || request == null ? null : request.headers().get(name);
    }

    /** 写入过滤器共享属性。 */
    @Override
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /** @return 请求是否已结束（响应已写回或不再继续转发） */
    @Override
    public boolean isCompleted() {
        return completed.get();
    }

    /** 标记请求已结束，后续过滤器不会再执行。 */
    @Override
    public void markCompleted() {
        completed.set(true);
    }

    /** 供内置和外挂 Filter 统一短路请求。 */
    @Override
    public void reject(int status, String body) {
        writeText(HttpResponseStatus.valueOf(status), body == null ? "" : body);
    }

    /**
     * 供过滤器短路时直接回写文本响应。
     * 例如鉴权失败、路由不存在时，可以直接调用这个方法结束请求。
     *
     * @param status       HTTP 状态码
     * @param responseBody 响应正文（text/plain）
     */
    public void writeText(HttpResponseStatus status, String responseBody) {
        writeText(status, responseBody, null);
    }

    /** 短路回写；rejectReason 非空时带上 X-Rover-Reject-Reason。已结束的请求不再写第二份。 */
    public void writeText(HttpResponseStatus status, String responseBody, String rejectReason) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
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
        channelContext.writeAndFlush(response);
        this.statusCode = status.code();
    }
}
