package com.rover.gateway.core.loadbalance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.gateway.core.config.GatewaySystemProperties;
import com.rover.gateway.core.route.RouteConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class StaticUpstreamClusterTest {

    @Test
    void defaultNettyRejectsHttpsUpstream() {
        RouteConfig route = new RouteConfig();
        route.setBusinessPrefix("/");
        route.setTargetUrls(List.of("https://example.com:443"));
        IllegalArgumentException err = assertThrows(
                IllegalArgumentException.class,
                () -> StaticUpstreamCluster.resolve(route));
        assertTrue(err.getMessage().contains("proxy.outbound=jdk"));
    }

    @Test
    void jdkOutboundKeepsHttpAndHttpsDistinctAndFormatsIpv6() {
        String previous = System.getProperty(GatewaySystemProperties.PROXY_OUTBOUND);
        System.setProperty(GatewaySystemProperties.PROXY_OUTBOUND, "jdk");
        try {
            RouteConfig route = new RouteConfig();
            route.setBusinessPrefix("/");
            route.setTargetUrls(List.of(
                    "http://example.com:80",
                    "https://example.com:80",
                    "http://[2001:db8::1]:8080"));

            var instances = StaticUpstreamCluster.resolve(route);

            assertEquals(3, instances.size());
            assertEquals("http://[2001:db8::1]:8080",
                    instances.get(2).getMetadata().get("baseUrl"));
        } finally {
            if (previous == null) {
                System.clearProperty(GatewaySystemProperties.PROXY_OUTBOUND);
            } else {
                System.setProperty(GatewaySystemProperties.PROXY_OUTBOUND, previous);
            }
        }
    }

    @Test
    void rebuildReplacesSnapshotSoRouteChangeIsVisible() {
        RouteConfig first = new RouteConfig();
        first.setId("demo");
        first.setBusinessPrefix("/api");
        first.setTargetUrl("http://old-host:8081");

        StaticUpstreamCluster.rebuild(List.of(first));
        assertEquals("old-host", StaticUpstreamCluster.instancesOf(first).get(0).getHost());

        RouteConfig updated = new RouteConfig();
        updated.setId("demo");
        updated.setBusinessPrefix("/api");
        updated.setTargetUrl("http://new-host:8081");

        StaticUpstreamCluster.rebuild(List.of(updated));
        assertEquals("new-host", StaticUpstreamCluster.instancesOf(updated).get(0).getHost());
        // 旧路由对象同一 clusterKey，读到的也是新快照
        assertEquals("new-host", StaticUpstreamCluster.instancesOf(first).get(0).getHost());
    }

    @Test
    void defaultNettyStillFormatsIpv6() {
        RouteConfig route = new RouteConfig();
        route.setBusinessPrefix("/");
        route.setTargetUrls(List.of("http://[2001:db8::1]:8080"));
        assertEquals("http://[2001:db8::1]:8080",
                StaticUpstreamCluster.resolve(route).get(0).getMetadata().get("baseUrl"));
    }

    @Test
    void rebuildEmptyDropsOldSnapshot() {
        RouteConfig route = new RouteConfig();
        route.setId("gone");
        route.setBusinessPrefix("/gone");
        route.setTargetUrl("http://old-host:8081");
        StaticUpstreamCluster.rebuild(List.of(route));

        StaticUpstreamCluster.rebuild(List.of());
        // 快照已空，instancesOf 会现场 resolve 当前 route 对象，不会死抱旧表
        assertEquals("old-host", StaticUpstreamCluster.instancesOf(route).get(0).getHost());
    }
}
