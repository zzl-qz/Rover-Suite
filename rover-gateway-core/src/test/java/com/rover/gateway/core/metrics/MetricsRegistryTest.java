package com.rover.gateway.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.spi.filter.FilterChain;
import com.rover.common.spi.filter.RequestContext;
import com.rover.gateway.core.filter.GatewayRequestContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Author: Daylight
 * Description: MetricsRegistry 聚合逻辑测试：窗口滚动、百分位、加和自洽、并发、边界
 */
class MetricsRegistryTest {

    /** 基础记录后，全局累计与状态码加和自洽。 */
    @Test
    void recordAggregatesAndSelfcheckPasses() {
        MetricsRegistry registry = new MetricsRegistry(new MetricsSettings());
        registry.record("route-a", 200, 10);
        registry.record("route-a", 200, 20);
        registry.record("route-a", 404, 5);
        registry.record("route-b", 500, 30);
        registry.record(null, 404, 1);

        String selfcheck = registry.selfcheckJson();
        assertTrue(selfcheck.contains("\"ok\":true"), "selfcheck 应全部通过: " + selfcheck);
    }

    /** 空数据时快照不出现 NaN/null，派生值为 0。 */
    @Test
    void emptySnapshotHasNoNaN() {
        MetricsRegistry registry = new MetricsRegistry(new MetricsSettings());
        String json = registry.snapshotJson();
        assertFalse(json.contains("NaN"), "快照不应出现 NaN");
        assertFalse(json.contains("null"), "快照不应出现 null");
        assertTrue(json.contains("\"avgMillis\":0"));
        assertTrue(json.contains("\"p95Millis\":0"));
        assertTrue(json.contains("\"windowRequests\":0"));
    }

    /** 百分位基于原始样本：1..100 的 P95 为 95。 */
    @Test
    void percentileComputesFromRawSamples() {
        long[] samples = new long[100];
        for (int i = 0; i < 100; i++) {
            samples[i] = i + 1;
        }
        assertEquals(95, MetricsRegistry.percentile(samples, 0.95));
        assertEquals(99, MetricsRegistry.percentile(samples, 0.99));
        assertEquals(0, MetricsRegistry.percentile(new long[0], 0.95));
        assertEquals(7, MetricsRegistry.percentile(new long[]{7}, 0.99));
    }

    /** 窗口滚动：当前秒有数据，超出窗口的秒归零。 */
    @Test
    void windowRollsAndExpiredSecondsReturnZero() {
        MetricsRegistry.TimeRing ring = new MetricsRegistry.TimeRing(300);
        long now = System.currentTimeMillis() / 1000;
        ring.record(now, 5, false);
        ring.record(now, 7, false);

        assertEquals(2, ring.countAt(now));
        assertEquals(0, ring.countAt(now - 301), "超出环形上限的秒应返回 0");
        assertEquals(0, ring.countAt(now + 1), "未来秒应返回 0");

        MetricsRegistry.TimeRing.WindowView view = ring.view(now, 60);
        assertEquals(2, view.requestCount);
        assertEquals(12, view.sumMillis);
        assertEquals(7, view.maxMillis);
    }

    /** 秒切换后旧秒数据不串入新秒（基线清零正确）。 */
    @Test
    void slotReuseDoesNotLeakOldData() {
        MetricsRegistry.TimeRing ring = new MetricsRegistry.TimeRing(4);
        long base = 1_000_000L;
        // 秒 base 写入 3 条
        ring.record(base, 10, false);
        ring.record(base, 10, false);
        ring.record(base, 10, false);
        // 秒 base+4 复用同一槽位
        ring.record(base + 4, 5, false);

        assertEquals(0, ring.countAt(base), "旧秒数据不应残留");
        assertEquals(1, ring.countAt(base + 4));
    }

    /** 并发累加不丢失：多线程 record 后总量精确相等且自洽。 */
    @Test
    void concurrentRecordLosesNothing() throws Exception {
        MetricsRegistry registry = new MetricsRegistry(new MetricsSettings());
        int threads = 8;
        int perThread = 2000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        registry.record(seed % 2 == 0 ? "route-a" : "route-b",
                                i % 10 == 0 ? 500 : 200, i % 50);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        String json = registry.snapshotJson();
        assertTrue(json.contains("\"requests\":" + (long) threads * perThread),
                "总量应精确等于并发写入数: " + json);
        assertTrue(registry.selfcheckJson().contains("\"ok\":true"), "并发后自洽校验应通过");
    }

    /** 负耗时被夹到 0，不产生负统计。 */
    @Test
    void negativeCostClampedToZero() {
        MetricsRegistry registry = new MetricsRegistry(new MetricsSettings());
        registry.record("route-a", 200, -100);
        String json = registry.snapshotJson();
        assertTrue(json.contains("\"avgMillis\":0"));
        assertTrue(registry.selfcheckJson().contains("\"ok\":true"));
    }

    /** 未匹配路由归入 __unmatched__ 维度，路由加和仍等于全局。 */
    @Test
    void unmatchedRequestsGroupedAndSumsMatch() {
        MetricsRegistry registry = new MetricsRegistry(new MetricsSettings());
        registry.record(null, 404, 1);
        registry.record("route-a", 200, 2);
        String json = registry.snapshotJson();
        assertTrue(json.contains(MetricsRegistry.UNMATCHED_ROUTE));
        assertTrue(registry.selfcheckJson().contains("\"ok\":true"));
    }

    /** 上游维度与路由实例分布：7 参 record 后快照含 upstreams/instances，且 selfcheck 通过。 */
    @Test
    void upstreamDimensionAndInstanceDistribution() {
        MetricsRegistry registry = new MetricsRegistry(new MetricsSettings());
        registry.record("route-a", 200, 12, "127.0.0.1:8081", 8, false, false);
        registry.record("route-a", 502, 30, "127.0.0.1:8082", 5, true, false);
        registry.record("route-a", 504, 40, "127.0.0.1:8081", 7, false, true);
        registry.record(null, 404, 1);

        String json = registry.snapshotJson();
        assertTrue(json.contains("\"upstreams\""), "快照应包含 upstreams: " + json);
        assertTrue(json.contains("\"instances\""), "路由快照应包含 instances 分布: " + json);
        assertTrue(json.contains("127.0.0.1:8081"));
        assertTrue(json.contains("127.0.0.1:8082"));
        assertTrue(registry.selfcheckJson().contains("\"ok\":true"),
                "上游维度与实例分布自洽校验应通过");
    }

    /** 在途上游 supplier 注入后能在快照中体现（近似连接池使用）。 */
    @Test
    void upstreamInFlightSupplierReflectedInSnapshot() {
        MetricsRegistry registry = new MetricsRegistry(new MetricsSettings());
        AtomicInteger inFlight = new AtomicInteger(3);
        registry.setUpstreamInFlightSupplier(inFlight::get);
        assertTrue(registry.snapshotJson().contains("\"upstreamInFlight\":3"));
    }

    /** MetricsFilter 开关关闭时直通且不计指标；开启时恰好记一次。 */
    @Test
    void metricsFilterRespectsSwitchAndRecordsOnce() throws Exception {
        MetricsSettings settings = new MetricsSettings();
        MetricsRegistry registry = new MetricsRegistry(settings);
        MetricsFilter filter = new MetricsFilter(registry);
        GatewayRequestContext context = newContext();

        AtomicInteger chainCalls = new AtomicInteger();
        FilterChain chain = new FilterChain() {
            @Override
            public void doFilter(RequestContext ctx) {
                chainCalls.incrementAndGet();
                ((GatewayRequestContext) ctx).setStatusCode(200);
                ((GatewayRequestContext) ctx).markCompleted();
            }
        };

        // 关闭：直通、不记录
        settings.setEnabled(false);
        filter.doFilter(context, chain);
        assertEquals(1, chainCalls.get());
        assertTrue(registry.snapshotJson().contains("\"requests\":0"));

        // 开启：记录一次
        settings.setEnabled(true);
        filter.doFilter(newContext(), chain);
        assertTrue(registry.snapshotJson().contains("\"requests\":1"));
    }

    /** 下游抛异常时 MetricsFilter 仍记录一次并向上传播异常。 */
    @Test
    void metricsFilterRecordsOnDownstreamException() {
        MetricsRegistry registry = new MetricsRegistry(new MetricsSettings());
        MetricsFilter filter = new MetricsFilter(registry);
        FilterChain failingChain = new FilterChain() {
            @Override
            public void doFilter(RequestContext ctx) throws Exception {
                throw new IllegalStateException("boom");
            }
        };
        assertThrows(IllegalStateException.class,
                () -> filter.doFilter(newContext(), failingChain));
        assertTrue(registry.snapshotJson().contains("\"requests\":1"),
                "异常请求也应恰好记录一次（按 500 归类）");
    }

    private static GatewayRequestContext newContext() {
        return new GatewayRequestContext(
                null,
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test"),
                "/test");
    }

    /** 防止误改：窗口上限常量与文档一致。 */
    @Test
    void ringCapacityMatchesSpec() {
        assertEquals(300, MetricsRegistry.RING_SECONDS);
        assertEquals(64, MetricsRegistry.RESERVOIR_SIZE);
    }
}
