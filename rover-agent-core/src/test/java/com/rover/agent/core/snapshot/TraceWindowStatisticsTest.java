package com.rover.agent.core.snapshot;

import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.investigation.EvidenceNarrator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceWindowStatisticsTest {
    @Test void v2RateUsesV2DenominatorRatherThanTheHealthyVersion() {
        var rows = new ArrayList<TraceRow>();
        for (int i = 0; i < 62; i++) { rows.add(row("v1", "h1", "uid", 1, 200, 1500)); }
        for (int i = 0; i < 58; i++) { rows.add(row("v2", "h2", "uid", 1, 503, 1500)); }
        var snapshot = new TraceSnapshot(true, 1, rows, 2000);
        var populations = TraceWindowStatistics.populations(snapshot, "/p", new TimeRange(1000, 2000));
        var v2 = populations.get(0);
        assertEquals("v2", v2.get("group")); assertEquals(58L, v2.get("sampleRequests"));
        assertEquals(58L, v2.get("sample5xx")); assertEquals(1.0, v2.get("sample5xxRate"));
        assertEquals(0.0, populations.get(1).get("sample5xxRate"));
        var text = EvidenceNarrator.traces(snapshot, "/p", new TimeRange(1000, 2000)).detail();
        assertTrue(text.contains("sample5xxRate=1.0（分子 58 / 分母 58）"));
        assertTrue(text.contains("不代表完整流量"));
    }

    @Test void reusedEndpointAndWindowBoundariesCannotMixPopulations() {
        var rows = List.of(row("v2", "same", "old", 1, 503, 1500), row("v2", "same", "new", 1, 200, 1600),
                row("v1", "same", "old", 1, 200, 1700), row("v2", "same", "old", 1, 503, 999));
        var populations = TraceWindowStatistics.populations(new TraceSnapshot(true, .1, rows, 2000), "/p", new TimeRange(1000, 2000));
        assertEquals(3, populations.size());
        assertEquals(1L, populations.get(0).get("sampleRequests"));
        assertEquals("RETAINED_TRACE_SAMPLE", populations.get(0).get("population"));
        assertTrue(TraceWindowStatistics.populations(new TraceSnapshot(true, 1, rows, 2000), "/other", new TimeRange(1000, 2000)).isEmpty());
    }

    @Test void oldTraceRowsRetainUnknownIdentityRatherThanBorrowingCurrentVersion() {
        var populations = TraceWindowStatistics.populations(new TraceSnapshot(true, .1,
                List.of(new TraceRow("old", "/p", 503, 1500)), 2000), "/p", new TimeRange(1000, 2000));
        assertNull(populations.get(0).get("group")); assertNull(populations.get(0).get("resourceUid"));
        assertEquals(java.util.Map.of("UNKNOWN", 1L), populations.get(0).get("responseOrigins"));
    }

    private static TraceRow row(String group, String host, String uid, long generation, int status, long at) {
        return new TraceRow("trace", "/p", status, at, new TraceObservation("route", uid, generation,
                "svc", group, host, "UPSTREAM", "NONE", null, true, List.of()));
    }
}
