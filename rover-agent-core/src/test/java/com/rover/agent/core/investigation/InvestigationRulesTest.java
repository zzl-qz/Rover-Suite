package com.rover.agent.core.investigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.Hypothesis;
import com.rover.agent.core.model.Verdict;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.snapshot.RouteUpstreamSnapshot;
import com.rover.agent.core.snapshot.TraceRow;
import com.rover.agent.core.snapshot.TraceSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 假设验证分支矩阵：结论、置信度与判断边界都必须能从只读事实直接推出。 */
class InvestigationRulesTest {

    private static final String PATH = "/api/demo/tt";

    @Test
    void confirmedRouteMissIsHighConfidence() {
        Findings findings = evaluate(null, true, DiscoveryMode.NAMESERVER, null, null);

        assertEquals(Verdict.CONFIRMED, hypothesis(findings, "H1").status());
        assertEquals(Confidence.HIGH, findings.confidence());
        assertEquals("当前路由表没有匹配该路径的路由。", findings.summary());
        assertEquals(1, findings.hypotheses().size());
    }

    @Test
    void unreadableRouteIsUnknownAndLowConfidence() {
        Findings findings = evaluate(null, false, DiscoveryMode.UNKNOWN, null, null);

        assertEquals(Verdict.UNKNOWN, hypothesis(findings, "H1").status());
        assertEquals(Confidence.LOW, findings.confidence());
        assertTrue(findings.summary().contains("路由数据不足"));
    }

    @Test
    void staticUpstreamRouteNeedsNoInstanceEvidence() {
        Findings findings = evaluate(route("/api/demo/tt", "", ""), true, DiscoveryMode.NAMESERVER, null, null);

        assertEquals(Verdict.REJECTED, hypothesis(findings, "H1").status());
        // 静态上游是路由事实，不是故障假设：只写进结论，不显示为已确认假设。
        assertTrue(findings.hypotheses().stream().noneMatch(item -> "H2".equals(item.id())));
        // 静态上游没有 Nameserver 实例假设，但「某台上游返回 5xx」这条例外仍要判定（此处指标未执行）。
        assertEquals(2, findings.hypotheses().size());
        assertEquals(Verdict.UNKNOWN, hypothesis(findings, "H6").status());
        assertTrue(findings.summary().contains("静态上游"));
        assertEquals(Confidence.LOW, findings.confidence());
    }

    @Test
    void nonNameserverDiscoveryRejectsInstanceEvidence() {
        Findings findings = evaluate(route("/api", "demo", ""), true, DiscoveryMode.NACOS, null, null);

        // 服务发现模式同样是判断范围，不是已确认故障。
        assertTrue(findings.hypotheses().stream().noneMatch(item -> "H2".equals(item.id())));
        assertEquals(2, findings.hypotheses().size());
        assertTrue(findings.summary().contains("NACOS"));
        assertTrue(findings.summary().contains("不能用于判断"));
    }

    @Test
    void missingInstanceEvidenceStaysUnknown() {
        Findings findings = evaluate(route("/api", "demo", ""), true, DiscoveryMode.NAMESERVER, null, null);

        assertEquals(Verdict.UNKNOWN, hypothesis(findings, "H3").status());
        assertTrue(findings.summary().contains("实例数据不足"));
    }

    @Test
    void unmatchedTargetServiceIsConfirmedAsMedium() {
        Findings findings = evaluate(route("/api/demo/tt", "demo", "11"), true, DiscoveryMode.NAMESERVER,
                List.of(new InstanceSnapshot("demo-service", "", "", "127.0.0.1", 8081, true, 100, true, 0L)), null);

        assertEquals(Verdict.CONFIRMED, hypothesis(findings, "H3").status());
        assertEquals("注册表中没有 demo / 11 的实例", hypothesis(findings, "H3").detail());
        assertEquals(Confidence.MEDIUM, findings.confidence());
        assertTrue(findings.summary().contains("demo / 11"));
        assertTrue(findings.summary().contains("尚缺少该路径近期的失败请求证据。"));
    }

    @Test
    void recentExactPath503IsReportedWithoutAttributingCause() {
        Findings findings = evaluate(route("/api/demo/tt", "demo", "11"), true, DiscoveryMode.NAMESERVER,
                List.of(), new TraceSnapshot(true, 1.0, List.of(
                        new TraceRow("t1", "/api/demo/tt", 503, System.currentTimeMillis())), 1L));

        Hypothesis h5 = hypothesis(findings, "H5");
        assertEquals(Verdict.CONFIRMED, h5.status());
        // 只陈述观察到该路径返回 503，不推断它来自下游。
        assertEquals("该路径近期返回 503", h5.statement());
        assertTrue(h5.detail().contains("仅凭状态码无法判定它由 Gateway 还是上游产生"));
        assertTrue(findings.summary().contains("无法仅凭状态码确定其原因"));
    }

    @Test
    void missingInstanceSummaryDoesNotClaimEvidenceIsAbsentWhenTracingIsOff() {
        Findings findings = evaluate(route("/api/demo/tt", "demo", "11"), true, DiscoveryMode.NAMESERVER,
                List.of(new InstanceSnapshot("demo-service", "", "", "127.0.0.1", 8081, true, 100, true, 0L)),
                new TraceSnapshot(false, 1.0, List.of(), 1L));

        assertTrue(findings.summary().contains("追踪已关闭，无法采集该路径的失败请求证据。"));
    }

    @Test
    void tracesUnavailableDisabledOrWithoutRecentRowsCannotVerifyPathStatus() {
        Findings unavailable = evaluate(route("/api", "demo", ""), true, DiscoveryMode.NAMESERVER, List.of(), null);
        assertEquals(Verdict.UNKNOWN, hypothesis(unavailable, "H5").status());

        Findings disabled = evaluate(route("/api", "demo", ""), true, DiscoveryMode.NAMESERVER, List.of(),
                new TraceSnapshot(false, 1.0, List.of(), 1L));
        assertEquals(Verdict.UNKNOWN, hypothesis(disabled, "H5").status());
        assertTrue(hypothesis(disabled, "H5").detail().contains("追踪已关闭"));

        // 只采到别的路径：该路径是否有请求无从判断，不能写成「排除」。
        Findings otherPath = evaluate(route("/api", "demo", ""), true, DiscoveryMode.NAMESERVER, List.of(),
                new TraceSnapshot(true, 1.0, List.of(
                        new TraceRow("t1", "/api/hello-other", 503, System.currentTimeMillis())), 1L));
        assertEquals(Verdict.UNKNOWN, hypothesis(otherPath, "H5").status());

        // 记录在五分钟窗口之外，同样只能判为无法验证。
        Findings stale = evaluate(route("/api", "demo", ""), true, DiscoveryMode.NAMESERVER, List.of(),
                new TraceSnapshot(true, 1.0, List.of(new TraceRow("t1", PATH, 503,
                        System.currentTimeMillis() - 600_000L)), 1L));
        assertEquals(Verdict.UNKNOWN, hypothesis(stale, "H5").status());
    }

    @Test
    void capturedRecentRequestsWithout503StillCannotExcludeThePathStatusHypothesis() {
        Findings findings = evaluate(route("/api", "demo", ""), true, DiscoveryMode.NAMESERVER, List.of(),
                new TraceSnapshot(true, 1.0, List.of(
                        new TraceRow("t1", PATH, 200, System.currentTimeMillis())), 1L));

        // 只采到一条 200 不能排除同段时间曾有 503：追踪有采样与容量限制。
        Hypothesis h5 = hypothesis(findings, "H5");
        assertEquals(Verdict.UNKNOWN, h5.status());
        assertTrue(h5.detail().contains("不能据此排除该路径曾返回 503"));
    }

    @Test
    void unhealthyInstancesAreNotTreatedAsMissing() {
        Findings findings = evaluate(route("/api", "demo", ""), true, DiscoveryMode.NAMESERVER,
                List.of(new InstanceSnapshot("demo", "blue", "", "127.0.0.1", 8081, false, 100, true, 0L)), null);

        assertEquals(Verdict.REJECTED, hypothesis(findings, "H3").status());
        assertEquals(Verdict.CONFIRMED, hypothesis(findings, "H4").status());
        assertTrue(findings.summary().contains("可能回退"));
    }

    @Test
    void healthyInstancesRejectBothFailureHypotheses() {
        Findings findings = evaluate(route("/api", "demo", ""), true, DiscoveryMode.NAMESERVER,
                List.of(new InstanceSnapshot("demo", "blue", "", "127.0.0.1", 8081, true, 100, true, 0L)), null);

        assertEquals(Verdict.REJECTED, hypothesis(findings, "H3").status());
        assertEquals(Verdict.REJECTED, hypothesis(findings, "H4").status());
        assertEquals(Confidence.LOW, findings.confidence());
        assertTrue(findings.summary().contains("1 个匹配的健康实例"));
    }

    @Test
    void verdictCountsDescribeInvestigationProgress() {
        Findings findings = evaluate(route("/api/demo/tt", "demo", "11"), true, DiscoveryMode.NAMESERVER,
                List.of(new InstanceSnapshot("demo-service", "", "", "127.0.0.1", 8081, true, 100, true, 0L)), null);

        // H6 在指标能力未执行时如实记为「无法验证」，不因缺少证据而默认排除。
        assertEquals("确认 1 项 / 排除 1 项 / 无法验证 2 项", InvestigationRules.describeVerdicts(findings.hypotheses()));
    }

    private static Findings evaluate(RouteSnapshot route, boolean routeRead, DiscoveryMode discoveryMode,
                                    List<InstanceSnapshot> instances, TraceSnapshot traces) {
        return evaluateWithUpstreams(route, routeRead, discoveryMode, instances, traces, null);
    }

    private static Findings evaluateWithUpstreams(RouteSnapshot route, boolean routeRead, DiscoveryMode discoveryMode,
                                                  List<InstanceSnapshot> instances, TraceSnapshot traces,
                                                  List<RouteUpstreamSnapshot> routeUpstreams) {
        return InvestigationRules.evaluate(new FindingsInput(PATH, route, routeRead, discoveryMode, instances, traces,
                routeUpstreams));
    }

    /** 一台稳定返回 5xx 的上游实例 + 一台正常实例：窗口样本都达到判定阈值。 */
    @Test
    void upstreamInstanceReturning5xxIsConfirmedAndNamed() {
        Findings findings = evaluateWithUpstreams(route("/api/order", "order-service", ""), true,
                DiscoveryMode.NAMESERVER, List.of(), null,
                List.of(upstream("order-1", 120, 0, 8.0, 20), upstream("order-2", 118, 42, 9.0, 22)));

        Hypothesis h6 = hypothesis(findings, "H6");
        assertEquals(Verdict.CONFIRMED, h6.status());
        assertTrue(h6.detail().contains("order-2"), "确认时必须点名异常实例，实际为 " + h6.detail());
        assertTrue(h6.detail().contains("42"), "确认时必须给出 5xx 计数，实际为 " + h6.detail());
        assertTrue(findings.summary().contains("order-2"), "结论同样要点名异常实例");
    }

    /**
     * H6 已点名异常实例时，结论里不能再留「数据不足以确定原因」——同一份报告不能一边说查不出来、
     * 一边给出确定的异常实例（真实故障注入时就是这样自相矛盾）。
     */
    @Test
    void confirmedUpstreamInstanceDropsTheInconclusiveWording() {
        Findings findings = evaluateWithUpstreams(route("/api/order", "order-service", ""), true,
                DiscoveryMode.NAMESERVER,
                List.of(new InstanceSnapshot("order-service", "", "", "127.0.0.1", 9202, true, 100, true, 0L)), null,
                List.of(upstream("127.0.0.1:9202", 35, 35, 2.7, 4)));

        assertEquals(Verdict.CONFIRMED, hypothesis(findings, "H6").status());
        assertTrue(findings.summary().contains("127.0.0.1:9202"), "实际为 " + findings.summary());
        assertFalse(findings.summary().contains("不足以确定"), "实际为 " + findings.summary());
    }

    @Test
    void upstreamSamplesBelowThresholdCannotBlameAnInstance() {
        Findings findings = evaluateWithUpstreams(route("/api/order", "order-service", ""), true,
                DiscoveryMode.NAMESERVER, List.of(), null,
                List.of(upstream("order-1", 2, 0, 8.0, 20), upstream("order-2", 3, 3, 9.0, 22)));

        // 请求数低于阈值时，5xx 只是小样本噪音：不能点名实例，也不能说健康。
        Hypothesis h6 = hypothesis(findings, "H6");
        assertEquals(Verdict.UNKNOWN, h6.status());
        assertTrue(h6.detail().contains("样本不足"), "实际为 " + h6.detail());
        assertTrue(findings.hypotheses().stream().noneMatch(item -> item.status() == Verdict.CONFIRMED
                && "H6".equals(item.id())));
    }

    @Test
    void emptyUpstreamWindowNeitherBlamesNorDeclaresRecovery() {
        Findings findings = evaluateWithUpstreams(route("/api/order", "order-service", ""), true,
                DiscoveryMode.NAMESERVER, List.of(), null, List.of());

        // 停止流量后窗口内没有任何样本：既不能说异常仍在，也不能说已经恢复。
        Hypothesis h6 = hypothesis(findings, "H6");
        assertEquals(Verdict.UNKNOWN, h6.status());
        assertTrue(h6.detail().contains("不能据此认定异常已恢复"), "实际为 " + h6.detail());
    }

    @Test
    void adequateHealthyUpstreamSamplesRejectTheInstanceHypothesis() {
        Findings findings = evaluateWithUpstreams(route("/api/order", "order-service", ""), true,
                DiscoveryMode.NAMESERVER, List.of(), null,
                List.of(upstream("order-1", 200, 0, 8.0, 20), upstream("order-2", 180, 0, 9.0, 22)));

        assertEquals(Verdict.REJECTED, hypothesis(findings, "H6").status());
    }

    @Test
    void unavailableUpstreamMetricsStayUnknown() {
        Findings findings = evaluate(route("/api/order", "order-service", ""), true, DiscoveryMode.NAMESERVER,
                List.of(), null);

        assertEquals(Verdict.UNKNOWN, hypothesis(findings, "H6").status());
        assertTrue(hypothesis(findings, "H6").detail().contains("不可用"));
    }

    private static RouteUpstreamSnapshot upstream(String hostPort, long windowRequests, long status5xx,
                                                  double avgMillis, long p95Millis) {
        return new RouteUpstreamSnapshot("order-route", hostPort, 60, windowRequests, status5xx, 0, 0,
                avgMillis, p95Millis, System.currentTimeMillis());
    }

    private static Hypothesis hypothesis(Findings findings, String id) {
        return findings.hypotheses().stream().filter(item -> id.equals(item.id())).findFirst()
                .orElseThrow(() -> new AssertionError("缺少假设 " + id));
    }

    private static RouteSnapshot route(String prefix, String serviceName, String group) {
        return new RouteSnapshot("demo-tt", prefix, serviceName, group, "", "", 1L);
    }
}