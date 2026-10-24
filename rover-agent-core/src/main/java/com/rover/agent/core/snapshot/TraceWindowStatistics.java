package com.rover.agent.core.snapshot;

import com.rover.agent.core.model.TimeRange;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 仅计算保留追踪样本内的资源分布；分母是同一身份的样本数，不能冒充完整流量错误率。 */
public final class TraceWindowStatistics {
    private TraceWindowStatistics() { }

    public static List<Map<String, Object>> populations(TraceSnapshot snapshot, String path, TimeRange requested) {
        long now = snapshot.observedAtMillis() > 0 ? snapshot.observedAtMillis() : System.currentTimeMillis();
        TimeRange range = requested == null || requested.isUnspecified()
                ? new TimeRange(Math.max(1, now - 300_000), now) : requested;
        Map<Identity, Counts> groups = new LinkedHashMap<>();
        for (TraceRow row : snapshot.rows() == null ? List.<TraceRow>of() : snapshot.rows()) {
            if (row == null || !java.util.Objects.equals(path, row.path()) || !range.contains(row.startMillis())) { continue; }
            var o = row.observation();
            var id = o == null ? new Identity(null, null, 0, null, null, null)
                    : new Identity(o.routeId(), o.resourceUid(), o.generation(), o.serviceName(), o.group(), o.hostPort());
            Counts counts = groups.computeIfAbsent(id, ignored -> new Counts());
            counts.requests++;
            if (row.statusCode() >= 500 && row.statusCode() < 600) { counts.errors++; }
            counts.statuses.merge(Integer.toString(row.statusCode()), 1L, Long::sum);
            counts.origins.merge(o == null || o.responseOrigin() == null ? "UNKNOWN" : o.responseOrigin(), 1L, Long::sum);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        groups.forEach((id, counts) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("routeId", id.routeId); row.put("resourceUid", id.resourceUid); row.put("generation", id.generation);
            row.put("serviceName", id.service); row.put("group", id.group); row.put("hostPort", id.host);
            row.put("sampleRequests", counts.requests); row.put("sample5xx", counts.errors);
            row.put("sample5xxRate", counts.errors / (double) counts.requests);
            row.put("statuses", counts.statuses); row.put("responseOrigins", counts.origins);
            row.put("population", "RETAINED_TRACE_SAMPLE"); result.add(row);
        });
        result.sort(java.util.Comparator.comparingLong((Map<String, Object> row) -> ((Number) row.get("sample5xx")).longValue()).reversed());
        return List.copyOf(result);
    }

    private record Identity(String routeId, String resourceUid, long generation, String service, String group, String host) { }
    private static final class Counts {
        long requests, errors;
        final Map<String, Long> statuses = new LinkedHashMap<>();
        final Map<String, Long> origins = new LinkedHashMap<>();
    }
}
