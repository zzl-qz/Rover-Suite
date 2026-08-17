package com.rover.gateway.core.loadbalance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rover.gateway.core.route.RouteConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class StaticUpstreamClusterTest {

    @Test
    void keepsHttpAndHttpsEndpointsDistinctAndFormatsIpv6() {
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
    }
}
