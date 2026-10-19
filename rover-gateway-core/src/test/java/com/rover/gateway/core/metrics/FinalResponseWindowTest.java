package com.rover.gateway.core.metrics;

import com.rover.gateway.core.trace.ResponseObservation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FinalResponseWindowTest {
    private static final long NOW = 1_000_123;

    @Test void sameStatusDifferentOriginsAndGatewayRejectionsStayDistinct() {
        var window = new FinalResponseWindow(NOW - 500_000);
        window.record(NOW, 503, 5, observation("UPSTREAM", "NONE", null, "v2", "uid-1", 1, true));
        window.record(NOW, 503, 5, observation("GATEWAY", "DISCOVERY", "NO_UPSTREAM", "v2", "uid-1", 1, true));
        window.record(NOW, 504, 5, observation("UPSTREAM", "NONE", null, "v1", "uid-1", 1, true));
        window.record(NOW, 504, 5, observation("GATEWAY", "UPSTREAM_TIMEOUT", null, "v2", "uid-1", 1, true));
        var result = window.snapshot("orders", 60, NOW);
        assertEquals(4L, result.get("finalRequests")); assertEquals(4L, result.get("final5xx"));
        assertEquals(2L, result.get("upstream5xx")); assertEquals(2L, result.get("gateway5xx"));
        assertEquals(Map.of("DISCOVERY", 1L, "UPSTREAM_TIMEOUT", 1L), result.get("failurePhases"));
        assertEquals(Map.of("NO_UPSTREAM", 1L), result.get("rejectReasons"));
        assertEquals("AVAILABLE", result.get("qualityStatus"));
    }

    @Test void retrySuccessIsOneFinalRequestAndTwoAttempts() {
        var window = new FinalResponseWindow(NOW - 500_000);
        var retry = new ResponseObservation("orders", "uid-1", 1, "svc", "v2", "host-b",
                "UPSTREAM", "NONE", null, true, List.of(
                new ResponseObservation.Attempt("host-a", "svc", "v2", 502, "GATEWAY", "CONNECT", 3, true),
                new ResponseObservation.Attempt("host-b", "svc", "v2", 200, "UPSTREAM", "NONE", 4, true)));
        window.record(NOW, 200, 10, retry);
        var result = window.snapshot("orders", 60, NOW);
        assertEquals(1L, result.get("finalRequests")); assertEquals(0L, result.get("final5xx"));
        assertEquals(2L, result.get("attemptCount"));
        assertEquals(Map.of("CONNECT", 1L), result.get("attemptFailurePhases"));
    }

    @Test void sameEndpointDoesNotMergeVersionsOrRecreatedResources() {
        var window = new FinalResponseWindow(NOW - 500_000);
        window.record(NOW, 200, 3, observation("UPSTREAM", "NONE", null, "v1", "old", 1, true));
        window.record(NOW, 503, 30, observation("UPSTREAM", "NONE", null, "v2", "old", 2, true));
        window.record(NOW, 200, 3, observation("UPSTREAM", "NONE", null, "v2", "new", 1, true));
        assertEquals(3, ((List<?>) window.snapshot("orders", 60, NOW).get("dimensions")).size());
    }

    @Test void expiredFaultAndStartupCoverageNeverLookLikeFullHealthyHistory() {
        var window = new FinalResponseWindow(NOW - 10_000);
        window.record(NOW - 70_000, 503, 3, observation("UPSTREAM", "NONE", null, "v2", "uid", 1, true));
        var result = window.snapshot("orders", 60, NOW);
        assertEquals(0L, result.get("finalRequests")); assertEquals("PARTIAL", result.get("qualityStatus"));
        assertEquals(NOW - 10_000, result.get("coverageFromMillis"));
        assertEquals("EMPTY", new FinalResponseWindow(NOW - 500_000).snapshot("orders", 60, NOW).get("qualityStatus"));
    }

    @Test void histogramMergesRawCountsAndPartialResponsesAreNotFullSuccess() {
        var window = new FinalResponseWindow(NOW - 500_000);
        for (int i = 0; i < 95; i++) { window.record(NOW - 1_000, 200, 2, observation("UPSTREAM", "NONE", null, "v1", "uid", 1, true)); }
        for (int i = 0; i < 5; i++) { window.record(NOW, 200, 1000, observation("UPSTREAM", "UPSTREAM_TIMEOUT", null, "v2", "uid", 1, false)); }
        var result = window.snapshot("orders", 60, NOW);
        assertEquals(2L, result.get("p95UpperBoundMillis")); // 不是两个实例 P95 的平均或最大值。
        assertEquals(100L, java.util.Arrays.stream((long[]) result.get("latencyBucketCounts")).sum());
        assertEquals(5L, result.get("incompleteResponses")); assertEquals(0L, result.get("final5xx"));
    }

    @Test void boundedCardinalityReportsMissingCountsAndSuppressesRate() {
        var window = new FinalResponseWindow(NOW - 500_000);
        for (int i = 0; i < 260; i++) { window.record(NOW, 503, 3, observation("UPSTREAM", "NONE", null, "v" + i, "uid", 1, true)); }
        var result = window.snapshot("orders", 60, NOW);
        assertEquals(256L, result.get("finalRequests")); assertEquals(4L, result.get("droppedDimensionRequests"));
        assertEquals("PARTIAL", result.get("qualityStatus")); assertNull(result.get("final5xxRate"));
    }

    @Test void concurrentWritesKeepHistogramAndRequestCountEqual() throws Exception {
        var window = new FinalResponseWindow(NOW - 500_000);
        var pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 4; i++) { pool.submit(() -> {
                for (int j = 0; j < 2000; j++) { window.record(NOW, 200, 2, observation("UPSTREAM", "NONE", null, "v1", "uid", 1, true)); }
            }); }
        } finally { pool.shutdown(); }
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        var result = window.snapshot("orders", 60, NOW);
        assertEquals(8000L, result.get("finalRequests"));
        assertEquals(8000L, java.util.Arrays.stream((long[]) result.get("latencyBucketCounts")).sum());
    }

    @Test void staleWriterCannotReplaceReusedSlot() {
        var window = new FinalResponseWindow(NOW - 500_000);
        var observation = observation("UPSTREAM", "NONE", null, "v1", "uid", 1, true);
        window.record(NOW, 200, 1, observation);
        window.record(NOW - 300_000, 503, 1, observation);
        assertEquals(1L, window.snapshot("orders", 60, NOW).get("finalRequests"));
        assertEquals(0L, window.snapshot("orders", 60, NOW).get("final5xx"));
    }

    @Test void versionRateAndHistogramMergeAcrossInstancesUsingRawCounts() {
        var window = new FinalResponseWindow(NOW - 500_000);
        window.record(NOW, 503, 4, observation("UPSTREAM", "NONE", null, "v2", "uid", 1, true));
        window.record(NOW, 200, 1000, new ResponseObservation("orders", "uid", 1, "svc", "v2", "other:8080",
                "UPSTREAM", "NONE", null, true, List.of()));
        var result = window.snapshot("orders", 60, NOW);
        var versions = (List<?>) result.get("versions");
        assertEquals(1, versions.size());
        var row = (Map<?, ?>) versions.get(0);
        assertEquals(2L, row.get("finalRequests")); assertEquals(1L, row.get("final5xx"));
        assertEquals(.5, row.get("final5xxRate")); assertEquals(1024L, row.get("p95UpperBoundMillis"));
        assertEquals(2L, java.util.Arrays.stream((long[]) row.get("latencyBucketCounts")).sum());
    }

    @Test void historicalQueryExcludesBoundarySecondsAndReportsRealCoverage() {
        var window = new FinalResponseWindow(NOW - 500_000);
        var o = observation("UPSTREAM", "NONE", null, "v2", "uid", 1, true);
        window.record(990_500, 503, 2, o); // 起点所在秒含区间外请求，整秒排除。
        window.record(991_500, 503, 2, o);
        window.record(992_500, 200, 2, o); // 终点所在秒同样排除。
        var result = window.snapshotRange("orders", 990_700, 992_600, NOW);
        assertEquals(991_000L, result.get("coverageFromMillis"));
        assertEquals(991_999L, result.get("coverageToMillis"));
        assertEquals(1L, result.get("finalRequests")); assertEquals(1L, result.get("final5xx"));
        assertEquals("PARTIAL", result.get("qualityStatus"));
        var expired = window.snapshotRange("orders", 1000, 5000, NOW);
        assertEquals("OUT_OF_RANGE", expired.get("qualityStatus")); assertNull(expired.get("coverageFromMillis"));
        assertThrows(IllegalArgumentException.class, () -> window.snapshotRange("orders", NOW, NOW + 1, NOW));
    }

    private static ResponseObservation observation(String origin, String phase, String reject, String group,
                                                     String uid, long generation, boolean complete) {
        return new ResponseObservation("orders", uid, generation, "svc", group, "same-host:8080",
                origin, phase, reject, complete, List.of());
    }
}
