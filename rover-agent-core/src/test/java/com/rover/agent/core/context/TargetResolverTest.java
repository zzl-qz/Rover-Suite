package com.rover.agent.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.SnapshotUnavailableException;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TargetResolverTest {

    private static final RouteSnapshot ROUTE = new RouteSnapshot("r1", "/api/demo/tt", "demo-service", "",
            "", 1L);
    private static final InstanceSnapshot INSTANCE = new InstanceSnapshot("demo-service", "", "10.0.0.7", 8080, true);

    private static TargetResolver resolver(TargetInterpreter interpreter) {
        return new TargetResolver(routePort(List.of(ROUTE)), instancePort(List.of(INSTANCE)), interpreter);
    }

    @Test
    void explicitTargetWinsOverQueryText() {
        TargetResolution resolution = resolver(TargetInterpreter.none())
                .resolve("为什么 /api/demo/tt 失败？", ResourceTarget.service("demo-service"));

        assertEquals(TargetType.SERVICE, resolution.target().type());
        assertEquals("demo-service", resolution.target().value());
        assertEquals("/api/demo/tt", resolution.investigationPath());
        assertFalse(resolution.needsClarification());
    }

    @Test
    void pathInQueryResolvesToExistingRoutePrefix() {
        TargetResolution resolution = resolver(TargetInterpreter.none())
                .resolve("为什么 /api/demo/tt/123 一直失败？", null);

        // 归一化为现有路由的业务前缀：诊断按路由前缀匹配，而不是用户随手写的子路径。
        assertEquals(ResourceTarget.route("/api/demo/tt"), resolution.target());
        assertEquals("/api/demo/tt", resolution.investigationPath());
    }

    @Test
    void unknownPathFallsThroughToClarification() {
        TargetResolution resolution = resolver(TargetInterpreter.none())
                .resolve("为什么 /api/unknown/xx 失败？", null);

        assertTrue(resolution.needsClarification());
        assertEquals(TargetType.UNKNOWN, resolution.target().type());
    }

    @Test
    void serviceNameInQueryResolvesToServiceTargetWithItsRoute() {
        TargetResolution resolution = resolver(TargetInterpreter.none())
                .resolve("demo-service 最近怎么这么慢？", null);

        assertEquals(ResourceTarget.service("demo-service"), resolution.target());
        assertEquals("/api/demo/tt", resolution.investigationPath());
    }

    @Test
    void knownServiceWithoutRouteAsksForClarification() {
        // order-service 没有任何路由指向它：没有取数口径，宁可澄清也不空转一轮调查。
        TargetResolution resolution = resolver(TargetInterpreter.none())
                .resolve("order-service 为什么一直失败？", null);

        assertTrue(resolution.needsClarification());
    }

    @Test
    void registeredInstanceAddressResolvesToInstanceTarget() {
        TargetResolution resolution = resolver(TargetInterpreter.none())
                .resolve("10.0.0.7:8080 上还有健康实例吗？", null);

        assertEquals(ResourceTarget.instance("10.0.0.7:8080"), resolution.target());
        assertEquals("/api/demo/tt", resolution.investigationPath());
    }

    @Test
    void modelAssistedInterpreterIsOnlyUsedWhenRulesFail() {
        TargetInterpreter noIdea = (query, routes, instances) -> Optional.empty();
        assertTrue(resolver(noIdea).resolve("帮我看看支付链路", null).needsClarification());

        TargetInterpreter picking = (query, routes, instances) -> Optional.of(ResourceTarget.route("/api/demo/tt"));
        TargetResolution resolution = resolver(picking).resolve("帮我看看支付链路", null);
        assertEquals(ResourceTarget.route("/api/demo/tt"), resolution.target());
        assertEquals("/api/demo/tt", resolution.investigationPath());
    }

    @Test
    void interpreterCannotInventTargetOutsideCandidates() {
        // 推断结果落在已知对象之外时按「没有把握」处理，不能把诊断带到不存在的对象上。
        TargetInterpreter fabricating = (query, routes, instances) -> Optional.of(ResourceTarget.unknown());
        assertTrue(resolver(fabricating).resolve("帮我看看支付链路", null).needsClarification());
    }

    @Test
    void unavailableSnapshotsDegradeToClarification() {
        TargetResolver resolver = new TargetResolver(routePort(null), instancePort(null), TargetInterpreter.none());

        TargetResolution resolution = resolver.resolve("为什么 /api/demo/tt 失败？", null);

        assertTrue(resolution.needsClarification());
    }

    @Test
    void explicitRouteTargetIsInvestigatedEvenWhenRouteIsUnknown() {
        // 显式给出的路径就是取数口径本身：照常调查，由结论如实报出"未匹配到路由"。
        TargetResolution resolution = resolver(TargetInterpreter.none())
                .resolve("", ResourceTarget.route("/api/not-deployed"));

        assertEquals(ResourceTarget.route("/api/not-deployed"), resolution.target());
        assertEquals("/api/not-deployed", resolution.investigationPath());
    }

    private static RouteReadPort routePort(List<RouteSnapshot> routes) {
        return new RouteReadPort() {
            @Override
            public List<RouteSnapshot> routes() {
                if (routes == null) {
                    throw new SnapshotUnavailableException("路由表不可用");
                }
                return routes;
            }

            @Override
            public DiscoveryMode discoveryMode() {
                return DiscoveryMode.UNKNOWN;
            }
        };
    }

    private static InstanceReadPort instancePort(List<InstanceSnapshot> instances) {
        return () -> {
            if (instances == null) {
                throw new SnapshotUnavailableException("实例列表不可用");
            }
            return instances;
        };
    }
}