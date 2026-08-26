package com.rover.gateway.core.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.constants.HttpConstants;
import com.rover.common.model.ServiceInstance;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.filter.circuit.CircuitBreakerSettings;
import com.rover.gateway.core.filter.circuit.InstanceCircuitBreaker;
import com.rover.gateway.core.loadbalance.RoundRobinLoadBalancer;
import com.rover.gateway.core.loadbalance.StaticUpstreamCluster;
import com.rover.gateway.core.metrics.MetricsRegistry;
import com.rover.gateway.core.metrics.MetricsSettings;
import com.rover.gateway.core.proxy.HttpProxyClient;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteMatcher;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RouteAndProxyFilterCircuitTest {

    @AfterEach
    void clearStaticCluster() {
        StaticUpstreamCluster.rebuild(List.of());
    }

    @Test
    void allOpenReturns503CircuitOpenNotNoUpstream() {
        RouteConfig route = staticRoute();
        StaticUpstreamCluster.rebuild(List.of(route));
        InstanceCircuitBreaker breaker = new InstanceCircuitBreaker(enabledAll());
        ServiceInstance only = StaticUpstreamCluster.instancesOf(route).get(0);
        for (int i = 0; i < 5; i++) {
            breaker.onResult(only, false);
        }

        MetricsRegistry metrics = new MetricsRegistry(new MetricsSettings());
        RecordingContext context = new RecordingContext("/api/hello");
        filter(route, breaker, metrics).doFilter(context.ctx, ignored -> null);

        FullHttpResponse response = context.readResponse();
        assertEquals(503, response.status().code());
        assertEquals(HttpConstants.REJECT_CIRCUIT_OPEN, response.headers().get(HttpConstants.REJECT_REASON_HEADER));
        assertTrue(metrics.snapshotJson().contains("\"circuitOpen\":1"));
        assertTrue(metrics.snapshotJson().contains("\"noUpstream\":0"));
        context.finish();
    }

    @Test
    void emptyDiscoveryReturns503NoUpstream() {
        RouteConfig route = new RouteConfig();
        route.setId("empty");
        route.setBusinessPrefix("/api");
        StaticUpstreamCluster.rebuild(List.of(route));

        MetricsRegistry metrics = new MetricsRegistry(new MetricsSettings());
        RecordingContext context = new RecordingContext("/api/hello");
        filter(route, new InstanceCircuitBreaker(enabledAll()), metrics)
                .doFilter(context.ctx, ignored -> null);

        FullHttpResponse response = context.readResponse();
        assertEquals(503, response.status().code());
        assertEquals(HttpConstants.REJECT_NO_UPSTREAM, response.headers().get(HttpConstants.REJECT_REASON_HEADER));
        assertTrue(metrics.snapshotJson().contains("\"noUpstream\":1"));
        assertTrue(metrics.snapshotJson().contains("\"circuitOpen\":0"));
        context.finish();
    }

    @Test
    void fourXxCountsAsAliveFiveXxCountsAsDead() {
        assertTrue(RouteAndProxyFilter.isUpstreamAlive(new HttpProxyClient.ProxyResult(200, 1, false, false), null));
        assertTrue(RouteAndProxyFilter.isUpstreamAlive(new HttpProxyClient.ProxyResult(404, 1, false, false), null));
        assertTrue(RouteAndProxyFilter.isUpstreamAlive(new HttpProxyClient.ProxyResult(499, 1, false, false), null));
        assertFalse(RouteAndProxyFilter.isUpstreamAlive(new HttpProxyClient.ProxyResult(500, 1, false, false), null));
        assertFalse(RouteAndProxyFilter.isUpstreamAlive(new HttpProxyClient.ProxyResult(200, 1, true, false), null));
        assertFalse(RouteAndProxyFilter.isUpstreamAlive(new HttpProxyClient.ProxyResult(200, 1, false, true), null));
        assertFalse(RouteAndProxyFilter.isUpstreamAlive(null, null));
        assertFalse(RouteAndProxyFilter.isUpstreamAlive(new HttpProxyClient.ProxyResult(200, 1, false, false), new RuntimeException("x")));
    }

    private static RouteAndProxyFilter filter(
            RouteConfig route, InstanceCircuitBreaker breaker, MetricsRegistry metrics) {
        return new RouteAndProxyFilter(
                new RouteMatcher(List.of(route)),
                null,
                DiscoveryType.STATIC,
                null,
                new RoundRobinLoadBalancer(),
                metrics,
                breaker);
    }

    private static RouteConfig staticRoute() {
        RouteConfig route = new RouteConfig();
        route.setId("cb-boundary");
        route.setBusinessPrefix("/api");
        route.setTargetUrl("http://10.0.0.8:18080");
        return route;
    }

    private static CircuitBreakerSettings enabledAll() {
        CircuitBreakerSettings settings = new CircuitBreakerSettings();
        settings.setEnabled(true);
        settings.setFailureThreshold(5);
        settings.setOpenSeconds(10);
        settings.setRecovery(CircuitBreakerSettings.ALL);
        return settings;
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
