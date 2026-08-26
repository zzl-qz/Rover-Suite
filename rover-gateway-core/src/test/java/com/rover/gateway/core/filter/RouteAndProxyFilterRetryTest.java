package com.rover.gateway.core.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.filter.circuit.CircuitBreakerSettings;
import com.rover.gateway.core.filter.circuit.InstanceCircuitBreaker;
import com.rover.gateway.core.filter.retry.RetrySettings;
import com.rover.gateway.core.loadbalance.RoundRobinLoadBalancer;
import com.rover.gateway.core.loadbalance.StaticUpstreamCluster;
import com.rover.gateway.core.metrics.MetricsRegistry;
import com.rover.gateway.core.metrics.MetricsSettings;
import com.rover.gateway.core.proxy.HttpProxyClient;
import com.rover.gateway.core.proxy.InboundBodyPipe;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteMatcher;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RouteAndProxyFilterRetryTest {

    @AfterEach
    void clearStaticCluster() {
        StaticUpstreamCluster.rebuild(List.of());
    }

    @Test
    void shouldRetryOnlyConnectFailBeforeBodySent() {
        InboundBodyPipe replayable = InboundBodyPipe.empty(16);
        assertTrue(RouteAndProxyFilter.shouldRetry(
                true, new HttpProxyClient.ProxyResult(502, 1, true, false), replayable));
        assertFalse(RouteAndProxyFilter.shouldRetry(
                false, new HttpProxyClient.ProxyResult(502, 1, true, false), replayable));
        assertFalse(RouteAndProxyFilter.shouldRetry(
                true, new HttpProxyClient.ProxyResult(500, 1, false, false), replayable));
        assertFalse(RouteAndProxyFilter.shouldRetry(
                true, new HttpProxyClient.ProxyResult(504, 1, false, true), replayable));
        assertFalse(RouteAndProxyFilter.shouldRetry(
                true, new HttpProxyClient.ProxyResult(429, 1, false, false), replayable));
        assertFalse(RouteAndProxyFilter.shouldRetry(
                true, new HttpProxyClient.ProxyResult(404, 1, false, false), replayable));
        assertFalse(RouteAndProxyFilter.shouldRetry(true, null, replayable));

        InboundBodyPipe attached = InboundBodyPipe.empty(16);
        attached.attach(chunk -> chunk.release());
        assertFalse(RouteAndProxyFilter.shouldRetry(
                true, new HttpProxyClient.ProxyResult(502, 1, true, false), attached));
    }

    @Test
    void connectFailSwitchesToOtherInstance() {
        RouteConfig route = twoUpstreams();
        StaticUpstreamCluster.rebuild(List.of(route));
        ScriptedProxy proxy = new ScriptedProxy(
                new HttpProxyClient.ProxyResult(502, 1, true, false),
                new HttpProxyClient.ProxyResult(200, 2, false, false));
        RecordingContext context = new RecordingContext("/api/hello");
        MetricsRegistry metrics = new MetricsRegistry(new MetricsSettings());

        filter(route, proxy, enabledRetry(), null, metrics)
                .doFilter(context.ctx, ignored -> null)
                .toCompletableFuture()
                .join();

        assertEquals(List.of(
                "http://10.0.0.1:18080/api/hello",
                "http://10.0.0.2:18080/api/hello"), proxy.urls);
        assertEquals(List.of(false, true), proxy.writeClientError);
        assertEquals(200, context.ctx.getStatusCode());
        assertTrue(metrics.snapshotJson().contains("\"connect\":1"));
        FullHttpResponse response = context.readResponse();
        assertEquals(200, response.status().code());
        assertNull(context.channel.readOutbound(), "不能先写 502 再写 200");
        context.finish();
    }

    @Test
    void disabledDoesNotRetry() {
        RouteConfig route = twoUpstreams();
        StaticUpstreamCluster.rebuild(List.of(route));
        ScriptedProxy proxy = new ScriptedProxy(new HttpProxyClient.ProxyResult(502, 1, true, false));
        RecordingContext context = new RecordingContext("/api/hello");

        filter(route, proxy, new RetrySettings(), null, null)
                .doFilter(context.ctx, ignored -> null)
                .toCompletableFuture()
                .join();

        assertEquals(1, proxy.urls.size());
        assertEquals(List.of(true), proxy.writeClientError);
        assertEquals(502, context.readResponse().status().code());
        context.finish();
    }

    @Test
    void singleInstanceDoesNotRetry() {
        RouteConfig route = oneUpstream();
        StaticUpstreamCluster.rebuild(List.of(route));
        ScriptedProxy proxy = new ScriptedProxy(new HttpProxyClient.ProxyResult(502, 1, true, false));
        RecordingContext context = new RecordingContext("/api/hello");

        filter(route, proxy, enabledRetry(), null, null)
                .doFilter(context.ctx, ignored -> null)
                .toCompletableFuture()
                .join();

        assertEquals(1, proxy.urls.size());
        assertEquals(List.of(true), proxy.writeClientError);
        context.finish();
    }

    @Test
    void fiveXxDoesNotRetry() {
        RouteConfig route = twoUpstreams();
        StaticUpstreamCluster.rebuild(List.of(route));
        ScriptedProxy proxy = new ScriptedProxy(new HttpProxyClient.ProxyResult(500, 1, false, false));
        RecordingContext context = new RecordingContext("/api/hello");

        filter(route, proxy, enabledRetry(), null, null)
                .doFilter(context.ctx, ignored -> null)
                .toCompletableFuture()
                .join();

        assertEquals(1, proxy.urls.size());
        context.finish();
    }

    @Test
    void requestTimeoutDoesNotRetry() {
        RouteConfig route = twoUpstreams();
        StaticUpstreamCluster.rebuild(List.of(route));
        ScriptedProxy proxy = new ScriptedProxy(new HttpProxyClient.ProxyResult(504, 1, false, true));
        RecordingContext context = new RecordingContext("/api/hello");

        filter(route, proxy, enabledRetry(), null, null)
                .doFilter(context.ctx, ignored -> null)
                .toCompletableFuture()
                .join();

        assertEquals(1, proxy.urls.size());
        context.finish();
    }

    @Test
    void bothConnectFailWritesOnce() {
        RouteConfig route = twoUpstreams();
        StaticUpstreamCluster.rebuild(List.of(route));
        ScriptedProxy proxy = new ScriptedProxy(
                new HttpProxyClient.ProxyResult(502, 1, true, false),
                new HttpProxyClient.ProxyResult(502, 1, true, false));
        RecordingContext context = new RecordingContext("/api/hello");

        filter(route, proxy, enabledRetry(), null, null)
                .doFilter(context.ctx, ignored -> null)
                .toCompletableFuture()
                .join();

        assertEquals(2, proxy.urls.size());
        assertEquals(List.of(false, true), proxy.writeClientError);
        assertEquals(502, context.readResponse().status().code());
        assertNull(context.channel.readOutbound());
        context.finish();
    }

    @Test
    void attachedBodyDoesNotRetryEvenOnConnectFail() {
        RouteConfig route = twoUpstreams();
        StaticUpstreamCluster.rebuild(List.of(route));
        ScriptedProxy proxy = new ScriptedProxy(new HttpProxyClient.ProxyResult(502, 1, true, false));
        proxy.attachBodyOnForward = true;
        RecordingContext context = new RecordingContext("/api/hello");
        context.ctx.getBodyPipe().offer(
                new DefaultLastHttpContent(Unpooled.copiedBuffer("pay", StandardCharsets.UTF_8)));

        filter(route, proxy, enabledRetry(), null, null)
                .doFilter(context.ctx, ignored -> null)
                .toCompletableFuture()
                .join();

        assertEquals(1, proxy.urls.size());
        context.finish();
    }

    @Test
    void firstFailStillCountsForCircuitBreaker() {
        RouteConfig route = twoUpstreams();
        StaticUpstreamCluster.rebuild(List.of(route));
        CircuitBreakerSettings settings = new CircuitBreakerSettings();
        settings.setEnabled(true);
        settings.setFailureThreshold(1);
        InstanceCircuitBreaker breaker = new InstanceCircuitBreaker(settings);
        ScriptedProxy proxy = new ScriptedProxy(
                new HttpProxyClient.ProxyResult(502, 1, true, false),
                new HttpProxyClient.ProxyResult(200, 1, false, false));
        RecordingContext context = new RecordingContext("/api/hello");

        filter(route, proxy, enabledRetry(), breaker, null)
                .doFilter(context.ctx, ignored -> null)
                .toCompletableFuture()
                .join();

        assertFalse(breaker.tryEnter(StaticUpstreamCluster.instancesOf(route).get(0)));
        assertEquals(200, context.ctx.getStatusCode());
        context.finish();
    }

    private static RouteAndProxyFilter filter(
            RouteConfig route,
            HttpProxyClient proxy,
            RetrySettings retry,
            InstanceCircuitBreaker breaker,
            MetricsRegistry metrics) {
        return new RouteAndProxyFilter(
                new RouteMatcher(List.of(route)),
                proxy,
                DiscoveryType.STATIC,
                null,
                new RoundRobinLoadBalancer(),
                metrics,
                breaker,
                retry);
    }

    private static RetrySettings enabledRetry() {
        RetrySettings settings = new RetrySettings();
        settings.setEnabled(true);
        return settings;
    }

    private static RouteConfig twoUpstreams() {
        RouteConfig route = new RouteConfig();
        route.setId("retry-two");
        route.setBusinessPrefix("/api");
        route.setTargetUrls(List.of("http://10.0.0.1:18080", "http://10.0.0.2:18080"));
        return route;
    }

    private static RouteConfig oneUpstream() {
        RouteConfig route = new RouteConfig();
        route.setId("retry-one");
        route.setBusinessPrefix("/api");
        route.setTargetUrl("http://10.0.0.1:18080");
        return route;
    }

    private static final class ScriptedProxy extends HttpProxyClient {
        private final List<HttpProxyClient.ProxyResult> script;
        private final List<String> urls = new ArrayList<>();
        private final List<Boolean> writeClientError = new ArrayList<>();
        private boolean attachBodyOnForward;

        private ScriptedProxy(HttpProxyClient.ProxyResult... results) {
            super((Void) null);
            this.script = new ArrayList<>(List.of(results));
        }

        @Override
        public CompletableFuture<ProxyResult> forwardAsync(
                ChannelHandlerContext ctx,
                HttpRequest request,
                String targetUrl,
                InboundBodyPipe body,
                boolean writeClientError) {
            urls.add(targetUrl);
            this.writeClientError.add(writeClientError);
            if (attachBodyOnForward && body != null) {
                body.attach(chunk -> chunk.release());
            }
            ProxyResult result = script.isEmpty()
                    ? new ProxyResult(502, 1, true, false)
                    : script.remove(0);
            if (writeClientError) {
                if (result.connectFail() || result.timeout() || result.statusCode() >= 500) {
                    writeDeferredError(ctx, result);
                } else {
                    writeOk(ctx, result.statusCode());
                }
            } else if (!result.connectFail() && !result.timeout() && result.statusCode() < 500) {
                writeOk(ctx, result.statusCode());
            }
            return CompletableFuture.completedFuture(result);
        }

        private static void writeOk(ChannelHandlerContext ctx, int status) {
            io.netty.handler.codec.http.DefaultFullHttpResponse response =
                    new io.netty.handler.codec.http.DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            HttpResponseStatus.valueOf(status),
                            Unpooled.EMPTY_BUFFER);
            response.headers().setInt(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH, 0);
            ctx.writeAndFlush(response);
        }
    }

    private static final class RecordingContext {
        private final EmbeddedChannel channel;
        private final GatewayRequestContext ctx;

        private RecordingContext(String path) {
            channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
            ctx = new GatewayRequestContext(
                    channel.pipeline().firstContext(),
                    new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path),
                    path);
        }

        private FullHttpResponse readResponse() {
            return channel.readOutbound();
        }

        private void finish() {
            channel.finish();
        }
    }
}
