package com.rover.agent.core.investigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.GatewayMetricSnapshot;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.snapshot.TraceRow;
import com.rover.agent.core.snapshot.TraceSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceNarratorTest {

    @Test
    void describesMatchedRouteAndMissingRoute() {
        RouteSnapshot route = new RouteSnapshot("demo-tt", "/api/demo/tt", "demo", "", "", "", 1L);

        assertEquals("id=demo-tt，前缀=/api/demo/tt，服务=demo，分组=全部分组",
                EvidenceNarrator.route(route).detail());
        assertEquals("当前路由表没有匹配项", EvidenceNarrator.route(null).detail());
    }

    /**
     * 静态路由的目标写在 {@code targetUrls} 里，服务名为空——不能因为「服务=」是空就判它转发不出去。
     *
     * <p>这条来自一次真实误判：网关的静态路由被读成「没有目标服务」，于是明明配置完好的路由
     * 被回答成「配了但走不通」。
     */
    @Test
    void staticRouteTargetsAreReportedInsteadOfLookingMisconfigured() {
        RouteSnapshot route = new RouteSnapshot("static-demo-api", "/api/static", "", "",
                "", "http://127.0.0.1:8081,http://127.0.0.1:8082", 1L);

        String detail = EvidenceNarrator.route(route).detail();

        assertTrue(detail.contains("静态目标=http://127.0.0.1:8081,http://127.0.0.1:8082"),
                "多静态目标必须出现在证据里，否则读的人只能看到空字段，实际为 " + detail);
        assertTrue(EvidenceNarrator.route(route).limitations().isEmpty(),
                "有静态目标就不是「没配上游」，不该给判断边界");
    }

    /** 两种上游都没有：这才是「匹配得到也转发不出去」，要作为事实明说，而不是留两个空字段。 */
    @Test
    void routeWithoutAnyUpstreamIsReportedAsUnroutable() {
        RouteSnapshot route = new RouteSnapshot("empty-route", "/api/empty", "", "", "", "", 1L);

        assertTrue(EvidenceNarrator.route(route).limitations().stream()
                        .anyMatch(item -> item.contains("无法转发")),
                "既没有服务也没有静态地址时必须明确说出来");
    }

    /** 路由清单：一次给出全部路由，模型不必靠猜前缀逐个探测（猜不到的前缀会被漏掉）。 */
    @Test
    void routeListReportsEveryRouteWithItsUpstream() {
        List<RouteSnapshot> all = List.of(
                new RouteSnapshot("demo-api", "/api", "demo-service", "", "", "", 1L),
                new RouteSnapshot("static-demo-api", "/api/static", "", "", "",
                        "http://127.0.0.1:8081,http://127.0.0.1:8082", 1L));

        String detail = EvidenceNarrator.routes(all).detail();

        assertTrue(detail.contains("共 2 条路由"), "实际为 " + detail);
        assertTrue(detail.contains("服务=demo-service"), "动态路由要给出目标服务，实际为 " + detail);
        assertTrue(detail.contains("静态目标=http://127.0.0.1:8081"), "静态路由要给出静态目标，实际为 " + detail);
    }

    @Test
    void blankGroupIsReportedAsUnrestrictedForRouteButDefaultForInstance() {
        RouteSnapshot route = new RouteSnapshot("demo", "/api", "demo", "", "", "", 1L);
        List<InstanceSnapshot> instances = List.of(new InstanceSnapshot("demo", "", "", "127.0.0.1", 8081, true, 100, true, 0L));

        assertTrue(EvidenceNarrator.route(route).detail().contains("分组=全部分组"));
        assertTrue(EvidenceNarrator.instances(instances, route).detail().contains("demo/默认组@127.0.0.1:8081(健康)"));
        assertTrue(EvidenceNarrator.instances(instances, route).detail().contains("目标 demo/全部分组 的健康实例 1 个"));
    }

    @Test
    void unknownDiscoveryModeCarriesJudgementBoundary() {
        assertTrue(EvidenceNarrator.discoveryMode(DiscoveryMode.UNKNOWN).limitations()
                .contains("Gateway 服务发现模式未知，不能用 Nameserver 实例推断上游状态。"));
        assertTrue(EvidenceNarrator.discoveryMode(DiscoveryMode.NAMESERVER).limitations().isEmpty());
    }

    @Test
    void metricsSnapshotKeepsGlobalRejectBoundary() {
        EvidenceNarration narration = EvidenceNarrator.metrics(new GatewayMetricSnapshot(12, 3, 4, 1L));

        assertEquals("最近窗口请求数=12，5xx=3，全局无上游拒绝累计=4", narration.detail());
        assertTrue(narration.limitations().contains("无上游拒绝数是全局累计值，不能单独归因到该路径。"));
    }

    @Test
    void tracesCountOnlyExactPathMatchesAndKeepSamplingBoundary() {
        TraceSnapshot snapshot = new TraceSnapshot(true, 1.0, List.of(
                new TraceRow("t1", "/api/hello", 503, 1L),
                new TraceRow("t2", "/api/hello-other", 503, 2L)), 3L);

        EvidenceNarration narration = EvidenceNarrator.traces(snapshot, "/api/hello");

        assertTrue(narration.detail().contains("最近五分钟精确路径匹配追踪 1 条"));
        assertTrue(narration.detail().contains("HTTP 503 traceId=t1"));
        assertFalse(narration.detail().contains("t2"));
        assertTrue(narration.limitations().stream().anyMatch(item -> item.contains("追踪受采样与缓冲容量影响")));
        assertFalse(narration.limitations().stream().anyMatch(item -> item.contains("追踪已关闭")));
    }

    @Test
    void tracesOutsideTheJudgementWindowAreReportedSeparately() {
        // 缓冲区容量不随判定窗口滚动：更早的记录要说明条数，但不能列成「最近记录」。
        TraceSnapshot snapshot = new TraceSnapshot(true, 1.0, List.of(
                new TraceRow("t1", "/api/hello", 503, 1L)), 600_001L);

        EvidenceNarration narration = EvidenceNarrator.traces(snapshot, "/api/hello");

        assertTrue(narration.detail().contains("最近五分钟精确路径匹配追踪 0 条"));
        assertTrue(narration.detail().contains("缓冲区另有 1 条更早记录，不计入判定"));
        assertFalse(narration.detail().contains("最近记录："));
    }

    @Test
    void disabledTracingIsAFactNotAFailure() {
        EvidenceNarration narration = EvidenceNarrator.traces(
                new TraceSnapshot(false, 0.5, List.of(), 1L), "/api/hello");

        assertTrue(narration.detail().startsWith("追踪已关闭；"));
        assertTrue(narration.limitations().contains("Gateway 追踪已关闭，当前无法采集新的请求记录。"));
    }

    @Test
    void unavailableWordingIsSharedForEverySource() {
        assertEquals("Gateway 路由数据不可用，无法确认路径命中的路由。", EvidenceNarrator.routeUnavailable());
        assertEquals("Nameserver 实例数据不可用，无法确认上游健康状态。", EvidenceNarrator.instancesUnavailable());
        assertEquals("Gateway 实时指标不可用。", EvidenceNarrator.metricsUnavailable());
        assertEquals("Gateway 追踪数据不可用。", EvidenceNarrator.tracesUnavailable());
    }
}