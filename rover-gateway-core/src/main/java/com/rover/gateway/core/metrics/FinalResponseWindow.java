package com.rover.gateway.core.metrics;

import com.rover.gateway.core.trace.ResponseObservation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按完成时间统计最终请求，独立于重试次数与 trace 采样。固定 300 个秒槽；每槽最多 256 个资源维度。
 * ponytail: 槽内短锁保证计数/直方图一致；若实测争用再分片，不能用负数夹零掩盖计数竞争。
 */
public final class FinalResponseWindow {
    static final int RETENTION_SECONDS = 300;
    static final int MAX_DIMENSIONS_PER_SECOND = 256;
    private static final long[] BOUNDS = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048,
            4096, 8192, 16384, 32768, 65536, Long.MAX_VALUE};
    private final Slot[] slots = new Slot[RETENTION_SECONDS];
    private final long startedAtMillis;

    public FinalResponseWindow(long startedAtMillis) {
        this.startedAtMillis = startedAtMillis;
        Arrays.setAll(slots, ignored -> new Slot());
    }

    public void record(long completedAtMillis, int status, long durationMillis, ResponseObservation observation) {
        long second = Math.floorDiv(completedAtMillis, 1000);
        Slot slot = slots[(int) Math.floorMod(second, slots.length)];
        synchronized (slot) {
            if (slot.second > second) { return; } // 延迟写入不能覆盖已经复用的新槽。
            if (slot.second != second) { slot.reset(second); }
            Key key = Key.of(observation);
            Counts counts = slot.rows.get(key);
            if (counts == null && slot.rows.size() >= MAX_DIMENSIONS_PER_SECOND) {
                slot.droppedDimensions++;
                return;
            }
            if (counts == null) { counts = new Counts(); slot.rows.put(key, counts); }
            counts.add(status, durationMillis, observation);
        }
    }

    /** [fromMillis, toMillis]；最前一秒对齐，明确暴露启动覆盖和维度截断。 */
    public Map<String, Object> snapshot(String routeId, int windowSeconds, long nowMillis) {
        int seconds = Math.min(RETENTION_SECONDS, Math.max(1, windowSeconds));
        long lastSecond = Math.floorDiv(nowMillis, 1000);
        long from = (lastSecond - seconds + 1) * 1000;
        return collect(routeId, from, nowMillis, Math.max(from, startedAtMillis), nowMillis, nowMillis);
    }

    /** 历史查询只取完整秒槽，绝不把边界秒的其他请求混入指定区间。 */
    public Map<String, Object> snapshotRange(String routeId, long fromMillis, long toMillis, long nowMillis) {
        if (fromMillis < 0 || toMillis < fromMillis || toMillis > nowMillis
                || toMillis - fromMillis > RETENTION_SECONDS * 1000L) {
            throw new IllegalArgumentException("历史窗口必须在过去且最长 300 秒");
        }
        long retainedFrom = (Math.floorDiv(nowMillis, 1000) - RETENTION_SECONDS + 1) * 1000;
        long from = Math.max(fromMillis, Math.max(startedAtMillis, retainedFrom));
        long coverageFrom = (Math.floorDiv(from, 1000) + (from % 1000 == 0 ? 0 : 1)) * 1000;
        long coverageTo = toMillis == nowMillis ? nowMillis : Math.floorDiv(toMillis + 1, 1000) * 1000 - 1;
        return collect(routeId, fromMillis, toMillis, coverageFrom, coverageTo, nowMillis);
    }

    private Map<String, Object> collect(String routeId, long requestedFrom, long requestedTo,
                                        long coverageFrom, long coverageTo, long nowMillis) {
        boolean outOfRange = coverageTo < coverageFrom;
        long firstSecond = Math.floorDiv(coverageFrom, 1000);
        long lastSecond = Math.floorDiv(coverageTo, 1000);
        Counts total = new Counts();
        Map<Key, Counts> dimensions = new LinkedHashMap<>();
        Map<Key, Counts> versions = new LinkedHashMap<>();
        long dropped = 0;
        for (long second = firstSecond; !outOfRange && second <= lastSecond; second++) {
            Slot slot = slots[(int) Math.floorMod(second, slots.length)];
            synchronized (slot) {
                if (slot.second != second) { continue; }
                dropped += slot.droppedDimensions;
                slot.rows.forEach((key, count) -> {
                    if (java.util.Objects.equals(routeId, key.routeId)) {
                        total.merge(count);
                        if (dimensions.containsKey(key) || dimensions.size() < MAX_DIMENSIONS_PER_SECOND) {
                            dimensions.computeIfAbsent(key, ignored -> new Counts()).merge(count);
                        }
                        Key version = new Key(key.routeId, key.resourceUid, key.generation, key.serviceName, key.group, null);
                        if (versions.containsKey(version) || versions.size() < MAX_DIMENSIONS_PER_SECOND) {
                            versions.computeIfAbsent(version, ignored -> new Counts()).merge(count);
                        }
                    }
                });
            }
        }
        Map<String, Object> result = total.json();
        result.put("schemaVersion", 1);
        result.put("routeId", routeId);
        result.put("requestedFromMillis", requestedFrom);
        result.put("requestedToMillis", requestedTo);
        result.put("coverageFromMillis", outOfRange ? null : coverageFrom);
        result.put("coverageToMillis", outOfRange ? null : coverageTo);
        result.put("observedAtMillis", nowMillis);
        result.put("timeBasis", "REQUEST_COMPLETED");
        result.put("resolutionMillis", 1000);
        result.put("samplingMode", "ALL_FINAL_REQUESTS");
        result.put("droppedDimensionRequests", dropped);
        result.put("dimensionsLimit", MAX_DIMENSIONS_PER_SECOND);
        result.put("dimensionsMayBeTruncated", dimensions.size() >= MAX_DIMENSIONS_PER_SECOND);
        if (dropped > 0 || outOfRange) { result.put("final5xxRate", null); }
        boolean partial = coverageFrom > requestedFrom || coverageTo < requestedTo || dropped > 0
                || dimensions.size() >= MAX_DIMENSIONS_PER_SECOND;
        result.put("qualityStatus", outOfRange ? "OUT_OF_RANGE" : partial ? "PARTIAL"
                : total.requests == 0 ? "EMPTY" : "AVAILABLE");
        List<String> limitations = new ArrayList<>();
        if (outOfRange) { limitations.add("本次启动及 300 秒保留期内没有覆盖该时段的完整秒槽，不能据零计数判断无故障。"); }
        else if (partial) { limitations.add("窗口仅覆盖保留期与本次启动之后的完整秒槽；请按实际 coverage 范围解读计数。"); }
        if (dropped > 0) { limitations.add("维度容量达到上限；请求数是下界，不能据此计算完整错误率或排除未列资源故障。"); }
        if (dimensions.size() >= MAX_DIMENSIONS_PER_SECOND) { limitations.add("详细维度最多列出 256 行，不能据列表排除未列资源故障。"); }
        result.put("limitations", limitations);
        List<Map<String, Object>> rows = new ArrayList<>();
        dimensions.forEach((key, counts) -> {
            Map<String, Object> row = counts.json();
            row.put("resourceUid", key.resourceUid); row.put("generation", key.generation);
            row.put("serviceName", key.serviceName); row.put("group", key.group);
            row.put("hostPort", key.hostPort); rows.add(row);
        });
        result.put("dimensions", rows);
        List<Map<String, Object>> versionRows = new ArrayList<>();
        versions.forEach((key, counts) -> {
            Map<String, Object> row = counts.json();
            row.put("resourceUid", key.resourceUid); row.put("generation", key.generation);
            row.put("serviceName", key.serviceName); row.put("group", key.group);
            row.put("aggregation", "SERVICE_VERSION"); versionRows.add(row);
        });
        result.put("versions", versionRows);
        return result;
    }

    private record Key(String routeId, String resourceUid, long generation, String serviceName,
                       String group, String hostPort) {
        static Key of(ResponseObservation o) {
            return new Key(o.routeId(), o.resourceUid(), o.generation(), o.serviceName(), o.group(), o.hostPort());
        }
    }

    private static final class Slot {
        long second = Long.MIN_VALUE;
        long droppedDimensions;
        final Map<Key, Counts> rows = new LinkedHashMap<>();
        void reset(long value) { second = value; rows.clear(); droppedDimensions = 0; }
    }

    private static final class Counts {
        long requests, final5xx, upstream5xx, gateway5xx, incomplete, attempts, sumMillis;
        final Map<String, Long> reasons = new LinkedHashMap<>();
        final Map<String, Long> phases = new LinkedHashMap<>();
        final Map<String, Long> origins = new LinkedHashMap<>();
        final Map<String, Long> attemptPhases = new LinkedHashMap<>();
        final long[] histogram = new long[BOUNDS.length];
        void add(int status, long cost, ResponseObservation o) {
            requests++; sumMillis += Math.max(0, cost);
            if (status >= 500 && status < 600) {
                final5xx++;
                if ("UPSTREAM".equals(o.responseOrigin())) { upstream5xx++; }
                if ("GATEWAY".equals(o.responseOrigin())) { gateway5xx++; }
            }
            if (!o.responseComplete()) { incomplete++; }
            increment(origins, o.responseOrigin()); increment(phases, o.failurePhase());
            increment(reasons, o.rejectReason());
            attempts += o.attempts().size();
            o.attempts().forEach(a -> increment(attemptPhases, a.failurePhase()));
            int index = 0;
            while (index < BOUNDS.length - 1 && cost > BOUNDS[index]) { index++; }
            histogram[index]++;
        }
        void merge(Counts c) {
            requests += c.requests; final5xx += c.final5xx; upstream5xx += c.upstream5xx;
            gateway5xx += c.gateway5xx; incomplete += c.incomplete; attempts += c.attempts; sumMillis += c.sumMillis;
            mergeMap(reasons, c.reasons); mergeMap(phases, c.phases); mergeMap(origins, c.origins);
            mergeMap(attemptPhases, c.attemptPhases);
            for (int i = 0; i < histogram.length; i++) { histogram[i] += c.histogram[i]; }
        }
        Map<String, Object> json() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("finalRequests", requests); result.put("final5xx", final5xx);
            result.put("upstream5xx", upstream5xx); result.put("gateway5xx", gateway5xx);
            result.put("incompleteResponses", incomplete); result.put("attemptCount", attempts);
            result.put("final5xxRate", requests == 0 ? null : final5xx / (double) requests);
            result.put("avgMillis", requests == 0 ? null : sumMillis / (double) requests);
            result.put("responseOrigins", Map.copyOf(origins)); result.put("failurePhases", Map.copyOf(phases));
            result.put("attemptFailurePhases", Map.copyOf(attemptPhases));
            result.put("rejectReasons", Map.copyOf(reasons));
            result.put("latencyBucketUpperBoundsMillis", BOUNDS.clone());
            result.put("latencyBucketCounts", histogram.clone());
            result.put("latencySamples", requests);
            result.put("p95Method", "HISTOGRAM_UPPER_BOUND");
            long rank = (long) Math.ceil(requests * .95), seen = 0;
            Long p95 = null;
            for (int i = 0; requests > 0 && i < histogram.length; i++) {
                seen += histogram[i]; if (seen >= rank) { p95 = BOUNDS[i]; break; }
            }
            result.put("p95UpperBoundMillis", p95);
            return result;
        }
        static void increment(Map<String, Long> map, String key) {
            if (key != null && !key.isBlank() && !"NONE".equals(key)) { map.merge(key, 1L, Long::sum); }
        }
        static void mergeMap(Map<String, Long> into, Map<String, Long> from) {
            from.forEach((key, value) -> into.merge(key, value, Long::sum));
        }
    }
}
