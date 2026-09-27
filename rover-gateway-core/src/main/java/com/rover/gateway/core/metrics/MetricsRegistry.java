package com.rover.gateway.core.metrics;

import com.rover.common.constants.HttpConstants;
import com.rover.common.constants.ManageApiPaths;
import com.rover.gateway.core.config.GatewayDefaults;
import com.rover.gateway.core.route.RouteTarget;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Author: Daylight
 * Description: 网关指标统一数据源。累计值用 LongAdder 无锁累加；窗口按秒聚合，
 * 环形数组实现（内存固定）；耗时百分位基于窗口内原始样本（蓄水池采样）计算。
 * 所有派生指标（QPS/平均/P95/路由维度）都从本类派生，保证数据自洽。
 */
public class MetricsRegistry {

    /** 环形数组固定长度（秒），即窗口上限 5 分钟。 */
    public static final int RING_SECONDS = GatewayDefaults.METRICS_WINDOW_SECONDS;

    /** 每秒耗时样本蓄水池容量，够算 P99 且内存极小。 */
    static final int RESERVOIR_SIZE = 64;

    /** 路由维度中"未匹配任何路由"的归类键。 */
    public static final String UNMATCHED_ROUTE = "__unmatched__";

    /**
     * 版本级汇总判定「样本是否足够」的下限，与 rover-agent-core 的
     * {@code InvestigationRules.MIN_INSTANCE_SAMPLE} 保持一致（=5）：
     * 低于该值只报告样本量，不据此下异常结论。
     */
    public static final int MIN_VERSION_SAMPLE = 5;

    /** 采集配置，支持热更新。 */
    final MetricsSettings settings;

    /** 启动纳秒时间（单调时钟），用于 uptime 与耗时统计。 */
    final long startNanos = System.nanoTime();

    /** 全局累计请求数。 */
    final LongAdder totalRequests = new LongAdder();

    /** 全局状态码累计（四类互斥且穷尽，加和恒等于 totalRequests）。 */
    final LongAdder status2xx = new LongAdder();
    final LongAdder status3xx = new LongAdder();
    final LongAdder status4xx = new LongAdder();
    final LongAdder status5xx = new LongAdder();

    /** 网关自身错误累计（按类型）。 */
    final LongAdder errRouteUnmatched = new LongAdder();
    final LongAdder errProxyTimeout = new LongAdder();
    final LongAdder errUpstreamConnect = new LongAdder();

    /** 拒绝计数：只在 503 路径加，metrics.enabled=false 也记，方便压测对原因。 */
    final LongAdder rejectInflightLimit = new LongAdder();
    final LongAdder rejectNoUpstream = new LongAdder();
    final LongAdder rejectCircuitOpen = new LongAdder();

    /** 因连接失败换台次数。关指标也记。 */
    final LongAdder retryConnect = new LongAdder();

    /** 活跃连接数（Netty 接入连接，只增只减不随窗口过期）。 */
    final AtomicInteger activeConnections = new AtomicInteger();

    /** 在途请求数（正在处理中未返回的请求）。 */
    final AtomicInteger inflightRequests = new AtomicInteger();

    /** 全局按秒环形窗口。 */
    final TimeRing globalRing = new TimeRing(RING_SECONDS);

    /** 路由维度统计，键为 routeId（未匹配归 UNMATCHED_ROUTE）。 */
    final ConcurrentHashMap<String, RouteMetrics> routes = new ConcurrentHashMap<>();

    /** 上游维度统计，键为 host:port，记录上游响应时间与连接失败/超时。 */
    final ConcurrentHashMap<String, UpstreamMetrics> upstreams = new ConcurrentHashMap<>();

    /** 在途上游请求数来源，由 HttpProxyClient 提供（近似连接池使用情况）。 */
    volatile IntSupplier upstreamInFlightSupplier = () -> 0;
    volatile Supplier<Map<String, Object>> discoveryStatusSupplier = () -> Map.of("supported", false);

    /** 路由声明版本来源：routeId -> 配置里声明的版本目标；未注入时返回空。 */
    volatile Function<String, List<RouteTarget>> routeTargetsSupplier = routeId -> List.of();

    /** live 路由 Top 缓存：降低每秒排序成本。 */
    volatile List<Map<String, Object>> liveTopRoutesCache = List.of();
    final AtomicLong liveTopRoutesCachedAtMillis = new AtomicLong(0);

    public MetricsRegistry(MetricsSettings settings) {
        this.settings = settings == null ? new MetricsSettings() : settings;
    }

    /** 当前配置快照（供端点展示）。 */
    public MetricsSettings getSettings() {
        return settings;
    }

    /** 注入在途上游请求数来源（HttpProxyClient）。 */
    public void setUpstreamInFlightSupplier(IntSupplier supplier) {
        this.upstreamInFlightSupplier = supplier == null ? () -> 0 : supplier;
    }

    /** 注入服务发现状态来源，供 metrics 和 Prometheus 导出。 */
    public void setDiscoveryStatusSupplier(Supplier<Map<String, Object>> supplier) {
        this.discoveryStatusSupplier = supplier == null ? () -> Map.of("supported", false) : supplier;
    }

    /** 注入路由声明版本来源（routeId -> RouteConfig.getTargets()），供「声明版本 vs 观测版本」核对。 */
    public void setRouteTargetsSupplier(Function<String, List<RouteTarget>> supplier) {
        this.routeTargetsSupplier = supplier == null ? routeId -> List.of() : supplier;
    }

    /** 路由配置里声明过的版本目标；未注入来源或路由未知时返回空列表。 */
    public List<RouteTarget> declaredTargets(String routeId) {
        if (routeId == null || routeId.isBlank()) {
            return List.of();
        }
        List<RouteTarget> targets = routeTargetsSupplier.apply(routeId);
        return targets == null ? List.of() : targets;
    }

    /**
     * 记录一次请求（无上游信息，用于路由未匹配/无可用上游等场景）。
     * 由 MetricsFilter 在请求结束时调用，内部绝不允许抛异常影响主链路。
     */
    public void record(String routeId, int statusCode, long costMillis) {
        record(routeId, statusCode, costMillis, null, 0, false, false, null);
    }

    /**
     * 记录一次请求（含上游维度，不含版本归属）。由 MetricsFilter 在请求结束时调用。
     */
    public void record(
            String routeId,
            int statusCode,
            long costMillis,
            String upstreamHostPort,
            long upstreamCostMillis,
            boolean connectFail,
            boolean timeout) {
        record(routeId, statusCode, costMillis, upstreamHostPort, upstreamCostMillis, connectFail, timeout, null);
    }

    /**
     * 记录一次请求（含上游维度与版本归属）。由 MetricsFilter 在请求结束时调用。
     *
     * @param routeId            命中的路由 id，未匹配传 null
     * @param statusCode         最终响应状态码
     * @param costMillis         请求总耗时（毫秒）
     * @param upstreamHostPort   命中的上游实例 host:port，未转发传 null
     * @param upstreamCostMillis 上游往返耗时（毫秒）
     * @param connectFail        上游是否连接失败
     * @param timeout            上游是否超时
     * @param group              本次灰度选中的版本分组；静态路由或未选版本传 null
     */
    public void record(
            String routeId,
            int statusCode,
            long costMillis,
            String upstreamHostPort,
            long upstreamCostMillis,
            boolean connectFail,
            boolean timeout,
            String group) {
        if (!settings.isEnabled()) {
            return;
        }
        long cost = Math.max(0, costMillis);
        long epochSecond = System.currentTimeMillis() / 1000;

        totalRequests.increment();
        addStatus(statusCode);

        String routeKey = routeId == null || routeId.isBlank() ? UNMATCHED_ROUTE : routeId;
        boolean gatewayError = routeId == null || routeId.isBlank();
        if (statusCode == 504) {
            errProxyTimeout.increment();
            gatewayError = true;
        } else if (statusCode == 502) {
            errUpstreamConnect.increment();
            gatewayError = true;
        }
        if (routeId == null || routeId.isBlank()) {
            errRouteUnmatched.increment();
        }

        globalRing.record(epochSecond, cost, gatewayError, statusCode);
        RouteMetrics routeMetrics = routes.computeIfAbsent(routeKey, key -> new RouteMetrics());
        routeMetrics.record(statusCode, epochSecond, cost);

        // 上游维度 + 路由实例分布：只有真实转发到上游才统计
        if (upstreamHostPort != null && !upstreamHostPort.isBlank()) {
            upstreams.computeIfAbsent(upstreamHostPort, key -> new UpstreamMetrics())
                    .record(epochSecond, upstreamCostMillis, connectFail, timeout);
            routeMetrics.recordInstance(upstreamHostPort, normalizeGroup(group));
            // 路由 × 实例维度：按真实 statusCode 归类，才能指出是哪台实例返回了 5xx
            routeMetrics.upstreamMetrics.computeIfAbsent(upstreamHostPort, key -> new UpstreamMetrics())
                    .record(epochSecond, upstreamCostMillis, statusCode, connectFail, timeout);
        }
    }

    /** 版本键归一化：null/空白统一成空串，表示未分版本（静态路由或默认组）。 */
    static String normalizeGroup(String group) {
        return group == null ? "" : group.trim();
    }

    /** 状态码归类累加，四类互斥穷尽。 */
    private void addStatus(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            status2xx.increment();
        } else if (statusCode >= 300 && statusCode < 400) {
            status3xx.increment();
        } else if (statusCode >= 400 && statusCode < 500) {
            status4xx.increment();
        } else {
            // 1xx/5xx 及以上都归 5xx 桶，保证四桶穷尽、加和恒等
            status5xx.increment();
        }
    }

    /** 记录一次连接建立（Netty channelActive）。 */
    public void connectionOpened() {
        if (!settings.isEnabled()) {
            return;
        }
        activeConnections.incrementAndGet();
    }

    /** 记录一次连接关闭（Netty channelInactive），夹到 0 防止并发下负数。 */
    public void connectionClosed() {
        if (!settings.isEnabled()) {
            return;
        }
        activeConnections.updateAndGet(v -> Math.max(0, v - 1));
    }

    /** 记录一个请求开始处理（在途 +1）。 */
    public void requestStarted() {
        if (!settings.isEnabled()) {
            return;
        }
        inflightRequests.incrementAndGet();
    }

    /** 记录一个请求处理结束（在途 -1），夹到 0。 */
    public void requestFinished() {
        if (!settings.isEnabled()) {
            return;
        }
        inflightRequests.updateAndGet(v -> Math.max(0, v - 1));
    }

    /** 记一次 503 原因。只在拒绝路径调用，关指标也加。 */
    public void recordReject(String reason) {
        recordReject(reason, null, null);
    }

    /**
     * 记一次 503 原因，并把它归因到路由与版本。
     *
     * <p>某版本组没有可接流实例（{@link HttpConstants#REJECT_NO_UPSTREAM}）或实例全熔断
     * （{@link HttpConstants#REJECT_CIRCUIT_OPEN}）时会被记为该版本的「容量问题」，
     * 与「真实转发后上游返回 5xx」分开——后者只在 {@link #record} 里按实例状态码统计。
     * 指标关闭时只累加全局计数，不建路由维度，避免无谓内存。
     *
     * @param reason  拒绝原因，见 {@link HttpConstants}
     * @param routeId 命中的路由 id，未匹配传 null
     * @param group   本次选中的版本分组；静态路由传 null
     */
    public void recordReject(String reason, String routeId, String group) {
        if (HttpConstants.REJECT_INFLIGHT_LIMIT.equals(reason)) {
            rejectInflightLimit.increment();
        } else if (HttpConstants.REJECT_NO_UPSTREAM.equals(reason)) {
            rejectNoUpstream.increment();
        } else if (HttpConstants.REJECT_CIRCUIT_OPEN.equals(reason)) {
            rejectCircuitOpen.increment();
        }
        if (!settings.isEnabled() || routeId == null || routeId.isBlank()) {
            return;
        }
        String groupKey = normalizeGroup(group);
        RouteMetrics routeMetrics = routes.computeIfAbsent(routeId, key -> new RouteMetrics());
        if (HttpConstants.REJECT_NO_UPSTREAM.equals(reason)) {
            routeMetrics.noUpstreamByGroup.computeIfAbsent(groupKey, key -> new LongAdder()).increment();
        } else if (HttpConstants.REJECT_CIRCUIT_OPEN.equals(reason)) {
            routeMetrics.circuitOpenByGroup.computeIfAbsent(groupKey, key -> new LongAdder()).increment();
        }
    }

    /** 记一次「连不上换台」。 */
    public void recordRetryConnect() {
        retryConnect.increment();
    }

    private final MetricsExporter exporter = new MetricsExporter(this);

    /** 组装 /_manage/metrics 的 JSON 内容。 */
    public String snapshotJson() {
        return exporter.snapshotJson();
    }

    /**
     * 轻量实时快照：给 Admin 1 秒轮询用。
     * 相对全量 metrics：不算 p99、不带上游 Top、路由 Top 5 秒缓存且不算路由 p95。
     */
    public String liveJson(int rangeSeconds) {
        return exporter.liveJson(rangeSeconds);
    }

    /** 组装 /_manage/metrics/routes 的 JSON 内容。 */
    public String routeMetricsJson(String routeId, int rangeSeconds) {
        return exporter.routeMetricsJson(routeId, rangeSeconds);
    }

    /** 指定路由下各上游实例的窗口观测；路由未知或窗口内无转发记录时返回空列表。 */
    public List<Map<String, Object>> routeUpstreamRows(String routeId, int rangeSeconds) {
        if (routeId == null || routeId.isBlank()) {
            return List.of();
        }
        RouteMetrics routeMetrics = routes.get(routeId);
        if (routeMetrics == null) {
            return List.of();
        }
        long nowSecond = System.currentTimeMillis() / 1000;
        int window = clampRange(rangeSeconds);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, UpstreamMetrics> entry : routeMetrics.upstreamMetrics.entrySet()) {
            Map<String, Object> row = entry.getValue()
                    .windowSnapshot(routeId, entry.getKey(), nowSecond, window);
            // 只输出窗口内确实有转发记录的实例，避免把历史实例当作当前观测
            if (((Number) row.get("windowRequests")).longValue() > 0) {
                // 版本归属：实例被选中时所处的 group（空串表示无版本/默认组）
                row.put("group", routeMetrics.instanceGroups.getOrDefault(entry.getKey(), ""));
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * 按版本聚合窗口观测：把「路由 × 实例」行按 group 汇总，并合并配置声明的版本目标，
     * 使「配置里声明了哪些版本」与「实际收到了哪些版本的流量」可以逐条核对。
     *
     * <p>{@code sampleSize} 即窗口内真实转发量，低于 {@link #MIN_VERSION_SAMPLE} 时
     * {@code sufficient=false}，调用方据此把「样本不足」与「确实没流量」分开。
     * {@code noUpstreamRejects}/{@code circuitOpenRejects} 是该版本组「没有可接流实例」与
     * 「实例全熔断」被 503 拒绝的累计次数——这是该版本的容量问题，不是该版本的上游 5xx。
     *
     * @param routeId      路由 id
     * @param instanceRows {@link #routeUpstreamRows} 的结果
     * @param declared     配置声明的版本目标
     * @param rangeSeconds 窗口秒数（已由 {@link #clampRange} 收口）
     */
    public List<Map<String, Object>> routeVersionRows(
            String routeId,
            List<Map<String, Object>> instanceRows,
            List<RouteTarget> declared,
            int rangeSeconds) {
        Map<String, Map<String, Object>> byGroup = new LinkedHashMap<>();
        for (RouteTarget target : declared) {
            Map<String, Object> row = byGroup.computeIfAbsent(
                    normalizeGroup(target.group()), MetricsRegistry::newVersionRow);
            row.put("declared", true);
            row.put("serviceName", target.serviceName());
            row.put("weight", target.weight());
        }
        for (Map<String, Object> instanceRow : instanceRows) {
            String group = normalizeGroup((String) instanceRow.get("group"));
            Map<String, Object> row = byGroup.computeIfAbsent(group, MetricsRegistry::newVersionRow);
            long requests = ((Number) instanceRow.getOrDefault("windowRequests", 0)).longValue();
            row.put("windowRequests", ((Number) row.get("windowRequests")).longValue() + requests);
            row.put("status5xx", ((Number) row.get("status5xx")).longValue() + status5xxOf(instanceRow));
            row.put("connectFail", ((Number) row.get("connectFail")).longValue()
                    + ((Number) instanceRow.getOrDefault("connectFail", 0)).longValue());
            row.put("timeout", ((Number) row.get("timeout")).longValue()
                    + ((Number) instanceRow.getOrDefault("timeout", 0)).longValue());
            double avgMillis = ((Number) instanceRow.getOrDefault("avgMillis", 0)).doubleValue();
            row.put("sumMillis", ((Number) row.get("sumMillis")).doubleValue() + avgMillis * requests);
            // 版本内多实例的 p95 取最大值，作为保守上界（不做跨实例样本合并）
            long instanceP95 = ((Number) instanceRow.getOrDefault("p95Millis", 0)).longValue();
            row.put("p95Millis", Math.max(((Number) row.get("p95Millis")).longValue(), instanceP95));
        }
        // 拒绝原因按版本归因：容量问题与上游 5xx 分开算
        RouteMetrics routeMetrics = routeId == null ? null : routes.get(routeId);
        if (routeMetrics != null) {
            mergeRejects(byGroup, routeMetrics.noUpstreamByGroup, "noUpstreamRejects");
            mergeRejects(byGroup, routeMetrics.circuitOpenByGroup, "circuitOpenRejects");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : byGroup.entrySet()) {
            Map<String, Object> row = entry.getValue();
            long requests = ((Number) row.get("windowRequests")).longValue();
            double sumMillis = ((Number) row.remove("sumMillis")).doubleValue();
            row.put("sampleSize", requests);
            row.put("sufficient", requests >= MIN_VERSION_SAMPLE);
            row.put("errorRate", requests == 0
                    ? 0 : round2(((Number) row.get("status5xx")).longValue() / (double) requests));
            row.put("avgMillis", requests == 0 ? 0 : round2(sumMillis / requests));
            long capacity = ((Number) row.get("noUpstreamRejects")).longValue()
                    + ((Number) row.get("circuitOpenRejects")).longValue();
            row.put("capacityProblem", capacity > 0);
            rows.add(row);
        }
        rows.sort((a, b) -> {
            long diff = ((Number) b.get("windowRequests")).longValue()
                    - ((Number) a.get("windowRequests")).longValue();
            if (diff != 0) {
                return Long.signum(diff);
            }
            return ((String) a.get("group")).compareTo((String) b.get("group"));
        });
        return rows;
    }

    /** 版本行骨架：字段齐全，便于调用方直接读取而不必判空。 */
    private static Map<String, Object> newVersionRow(String group) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("group", group);
        row.put("declared", false);
        row.put("serviceName", null);
        row.put("weight", null);
        row.put("windowRequests", 0L);
        row.put("status5xx", 0L);
        row.put("connectFail", 0L);
        row.put("timeout", 0L);
        row.put("p95Millis", 0L);
        row.put("noUpstreamRejects", 0L);
        row.put("circuitOpenRejects", 0L);
        row.put("sumMillis", 0.0);
        return row;
    }

    /** 把某版本维度的拒绝累计并入版本行；该版本没有流量行时也要建出来。 */
    private static void mergeRejects(
            Map<String, Map<String, Object>> byGroup,
            Map<String, LongAdder> rejects,
            String field) {
        for (Map.Entry<String, LongAdder> entry : rejects.entrySet()) {
            Map<String, Object> row = byGroup.computeIfAbsent(entry.getKey(), MetricsRegistry::newVersionRow);
            row.put(field, ((Number) row.get(field)).longValue() + entry.getValue().sum());
        }
    }

    /** 取实例行的 status.5xx，兼容字段缺失。 */
    private static long status5xxOf(Map<String, Object> instanceRow) {
        Object status = instanceRow.get("status");
        if (status instanceof Map<?, ?> map && map.get("5xx") instanceof Number number) {
            return number.longValue();
        }
        return 0;
    }

    /** 自洽性自检：校验各加和关系，返回 JSON。 */
    public String selfcheckJson() {
        return exporter.selfcheckJson();
    }

    /**
     * Prometheus 文本格式导出（不内置依赖，只是格式转换）。
     * 供有需要的用户对接外部 Grafana/Prometheus，与 snapshotJson 同一数据源派生。
     */
    public String prometheusText() {
        return exporter.prometheusText();
    }

    static int clampRange(int rangeSeconds) {
        if (rangeSeconds <= ManageApiPaths.LIVE_RANGE_1M) {
            return ManageApiPaths.LIVE_RANGE_1M;
        }
        return ManageApiPaths.LIVE_RANGE_5M;
    }

    /** 合并窗口内样本算百分位，基于原始样本而非平均的平均。 */
    static long percentile(long[] samples, double ratio) {
        if (samples.length == 0) {
            return 0;
        }
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(ratio * sorted.length) - 1;
        index = Math.max(0, Math.min(sorted.length - 1, index));
        return sorted[index];
    }

    /** 保留两位小数，路由/上游 snapshot 与导出共用。 */
    static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * 按秒环形窗口。槽位存"单调累加器 + 基线"，读时用 delta 得出该秒数据，
     * 清零只发生在秒切换瞬间且持槽位锁，正常请求路径无锁竞争。
     */
    static final class TimeRing {
        private final Slot[] slots;

        TimeRing(int seconds) {
            this.slots = new Slot[seconds];
            for (int i = 0; i < seconds; i++) {
                slots[i] = new Slot();
            }
        }

        /** 记录一个样本到对应秒槽位。 */
        void record(long epochSecond, long costMillis, boolean error) {
            record(epochSecond, costMillis, error, 200, false, false);
        }

        /** 记录一个样本，并按状态码归进近窗四桶。 */
        void record(long epochSecond, long costMillis, boolean error, int statusCode) {
            record(epochSecond, costMillis, error, statusCode, false, false);
        }

        /**
         * 完整重载：额外把连接失败/超时纳入按秒窗口，使它们也能给出窗口口径而非累计值。
         * 旧重载统一委托到这里并传 false，保证既有调用点行为不变。
         */
        void record(long epochSecond, long costMillis, boolean error, int statusCode,
                    boolean connectFail, boolean timeout) {
            Slot slot = slots[(int) Math.floorMod(epochSecond, slots.length)];
            if (slot.stamp != epochSecond) {
                synchronized (slot) {
                    if (slot.stamp != epochSecond) {
                        // 先取基线再发布新 stamp，保证看到新 stamp 的线程必看到新基线
                        slot.baseCount = slot.count.sum();
                        slot.baseSum = slot.sumMillis.sum();
                        slot.baseErrors = slot.errors.sum();
                        slot.base2xx = slot.s2.sum();
                        slot.base3xx = slot.s3.sum();
                        slot.base4xx = slot.s4.sum();
                        slot.base5xx = slot.s5.sum();
                        slot.baseConnectFail = slot.connectFailCount.sum();
                        slot.baseTimeout = slot.timeoutCount.sum();
                        slot.maxMillis.set(0);
                        slot.sampleSize.set(0);
                        slot.sampleCount.set(0);
                        slot.stamp = epochSecond;
                    }
                }
            }
            slot.count.increment();
            slot.sumMillis.add(costMillis);
            if (error) {
                slot.errors.increment();
            }
            if (connectFail) {
                slot.connectFailCount.increment();
            }
            if (timeout) {
                slot.timeoutCount.increment();
            }
            addStatusBucket(slot, statusCode);
            long currentMax = slot.maxMillis.get();
            while (costMillis > currentMax) {
                if (slot.maxMillis.compareAndSet(currentMax, costMillis)) {
                    break;
                }
                currentMax = slot.maxMillis.get();
            }
            reservoirAdd(slot, costMillis);
        }

        private static void addStatusBucket(Slot slot, int statusCode) {
            if (statusCode >= 200 && statusCode < 300) {
                slot.s2.increment();
            } else if (statusCode >= 300 && statusCode < 400) {
                slot.s3.increment();
            } else if (statusCode >= 400 && statusCode < 500) {
                slot.s4.increment();
            } else {
                slot.s5.increment();
            }
        }

        /** 蓄水池采样（Algorithm R），固定内存保留代表性耗时样本。 */
        private void reservoirAdd(Slot slot, long costMillis) {
            long seen = slot.sampleCount.incrementAndGet();
            if (seen <= RESERVOIR_SIZE) {
                slot.samples[(int) (seen - 1)] = costMillis;
                slot.sampleSize.incrementAndGet();
            } else {
                long replaceIndex = ThreadLocalRandom.current().nextLong(seen);
                if (replaceIndex < RESERVOIR_SIZE) {
                    slot.samples[(int) replaceIndex] = costMillis;
                }
            }
        }

        /** 某一秒的请求数（过期秒返回 0）。 */
        long countAt(long epochSecond) {
            Slot slot = slots[(int) Math.floorMod(epochSecond, slots.length)];
            return slot.stamp == epochSecond ? slot.count.sum() - slot.baseCount : 0;
        }

        /** 汇总窗口内各秒数据。 */
        WindowView view(long nowSecond, int windowSeconds) {
            WindowView view = new WindowView();
            List<Long> sampleList = new ArrayList<>();
            long actualSeconds = 0;
            for (long second = nowSecond - windowSeconds + 1; second <= nowSecond; second++) {
                Slot slot = slots[(int) Math.floorMod(second, slots.length)];
                if (slot.stamp != second) {
                    continue;
                }
                long count = slot.count.sum() - slot.baseCount;
                long sum = slot.sumMillis.sum() - slot.baseSum;
                long errs = slot.errors.sum() - slot.baseErrors;
                long s2 = slot.s2.sum() - slot.base2xx;
                long s3 = slot.s3.sum() - slot.base3xx;
                long s4 = slot.s4.sum() - slot.base4xx;
                long s5 = slot.s5.sum() - slot.base5xx;
                long cf = slot.connectFailCount.sum() - slot.baseConnectFail;
                long to = slot.timeoutCount.sum() - slot.baseTimeout;
                // 秒切换瞬间的极小并发误差可能导致 delta 为负，夹到 0 保证展示自洽
                count = Math.max(0, count);
                sum = Math.max(0, sum);
                errs = Math.max(0, errs);
                view.requestCount += count;
                view.sumMillis += sum;
                view.errorCount += errs;
                view.status2xx += Math.max(0, s2);
                view.status3xx += Math.max(0, s3);
                view.status4xx += Math.max(0, s4);
                view.status5xx += Math.max(0, s5);
                view.connectFail += Math.max(0, cf);
                view.timeout += Math.max(0, to);
                view.maxMillis = Math.max(view.maxMillis, slot.maxMillis.get());
                actualSeconds++;
                int size = Math.min(slot.sampleSize.get(), RESERVOIR_SIZE);
                for (int i = 0; i < size; i++) {
                    sampleList.add(slot.samples[i]);
                }
            }
            view.actualSeconds = Math.max(1, actualSeconds);
            view.samples = toPrimitive(sampleList);
            return view;
        }

        private static long[] toPrimitive(List<Long> list) {
            long[] array = new long[list.size()];
            for (int i = 0; i < list.size(); i++) {
                array[i] = list.get(i);
            }
            return array;
        }

        /** 单个秒槽位。 */
        static final class Slot {
            volatile long stamp;
            volatile long baseCount;
            volatile long baseSum;
            volatile long baseErrors;
            volatile long base2xx;
            volatile long base3xx;
            volatile long base4xx;
            volatile long base5xx;
            volatile long baseConnectFail;
            volatile long baseTimeout;
            final LongAdder count = new LongAdder();
            final LongAdder sumMillis = new LongAdder();
            final LongAdder errors = new LongAdder();
            final LongAdder s2 = new LongAdder();
            final LongAdder s3 = new LongAdder();
            final LongAdder s4 = new LongAdder();
            final LongAdder s5 = new LongAdder();
            final LongAdder connectFailCount = new LongAdder();
            final LongAdder timeoutCount = new LongAdder();
            final AtomicLong maxMillis = new AtomicLong();
            final long[] samples = new long[RESERVOIR_SIZE];
            final AtomicInteger sampleSize = new AtomicInteger();
            final AtomicLong sampleCount = new AtomicLong();
        }

        /** 窗口汇总结果。 */
        static final class WindowView {
            long requestCount;
            long sumMillis;
            long errorCount;
            long maxMillis;
            long actualSeconds;
            long status2xx;
            long status3xx;
            long status4xx;
            long status5xx;
            long connectFail;
            long timeout;
            long[] samples = new long[0];
        }
    }

    /** 路由维度统计：累计 + 状态码 + 按秒窗口 + 上游实例分布。 */
    static final class RouteMetrics {
        final LongAdder total = new LongAdder();
        final LongAdder s2 = new LongAdder();
        final LongAdder s3 = new LongAdder();
        final LongAdder s4 = new LongAdder();
        final LongAdder s5 = new LongAdder();
        final TimeRing ring = new TimeRing(RING_SECONDS);
        /** 命中的上游实例分布，键为 host:port，用于观察负载均衡是否均匀。 */
        final ConcurrentHashMap<String, LongAdder> instanceCounts = new ConcurrentHashMap<>();
        /** 路由 × 上游实例的窗口观测，键为 host:port，用于定位是哪台实例返回了 5xx。 */
        final ConcurrentHashMap<String, UpstreamMetrics> upstreamMetrics = new ConcurrentHashMap<>();
        /** 路由 × 实例的版本归属：host:port -> group（空串表示无版本/默认组）。 */
        final ConcurrentHashMap<String, String> instanceGroups = new ConcurrentHashMap<>();
        /** 版本维度的「没有可接流实例」拒绝累计，键为 group；属容量问题而非上游 5xx。 */
        final ConcurrentHashMap<String, LongAdder> noUpstreamByGroup = new ConcurrentHashMap<>();
        /** 版本维度的「实例全熔断」拒绝累计，键为 group。 */
        final ConcurrentHashMap<String, LongAdder> circuitOpenByGroup = new ConcurrentHashMap<>();

        void record(int statusCode, long epochSecond, long costMillis) {
            total.increment();
            if (statusCode >= 200 && statusCode < 300) {
                s2.increment();
            } else if (statusCode >= 300 && statusCode < 400) {
                s3.increment();
            } else if (statusCode >= 400 && statusCode < 500) {
                s4.increment();
            } else {
                s5.increment();
            }
            ring.record(epochSecond, costMillis, statusCode >= 500, statusCode);
        }

        void recordInstance(String hostPort, String group) {
            instanceCounts.computeIfAbsent(hostPort, key -> new LongAdder()).increment();
            instanceGroups.putIfAbsent(hostPort, group);
        }

        boolean statusSumEqualsTotal() {
            return s2.sum() + s3.sum() + s4.sum() + s5.sum() == total.sum();
        }

        long windowRequestCount(long nowSecond, int windowSeconds) {
            TimeRing.WindowView view = ring.view(nowSecond, windowSeconds);
            return view.requestCount;
        }

        Map<String, Object> snapshot(String routeId, long nowSecond, int windowSeconds) {
            TimeRing.WindowView view = ring.view(nowSecond, windowSeconds);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("routeId", routeId);
            row.put("requests", total.sum());
            row.put("windowRequests", view.requestCount);
            row.put("qps", round2(view.requestCount / (double) view.actualSeconds));
            row.put("lastSecondRequests", ring.countAt(nowSecond - 1));
            // 错误率口径：仅 5xx 计为服务错误，分母为窗口请求数
            row.put("errorRate", view.requestCount == 0
                    ? 0 : round2(view.errorCount / (double) view.requestCount));
            row.put("avgMillis", view.requestCount == 0 ? 0 : round2(view.sumMillis / (double) view.requestCount));
            row.put("p95Millis", percentile(view.samples, 0.95));
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("2xx", s2.sum());
            status.put("3xx", s3.sum());
            status.put("4xx", s4.sum());
            status.put("5xx", s5.sum());
            row.put("status", status);
            // 上游实例分布：负载均衡是否均匀
            Map<String, Long> instances = new LinkedHashMap<>();
            for (Map.Entry<String, LongAdder> entry : instanceCounts.entrySet()) {
                instances.put(entry.getKey(), entry.getValue().sum());
            }
            row.put("instances", instances);
            return row;
        }

        /** live 用：窗口 + 上一秒；不算 p95，避免每秒对每条 Top 路由排序样本。 */
        Map<String, Object> liveSnapshot(String routeId, long nowSecond, int windowSeconds) {
            TimeRing.WindowView view = ring.view(nowSecond, windowSeconds);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("routeId", routeId);
            row.put("lastSecondRequests", ring.countAt(nowSecond - 1));
            row.put("windowRequests", view.requestCount);
            row.put("errorRate", view.requestCount == 0
                    ? 0 : round2(view.errorCount / (double) view.requestCount));
            row.put("avgMillis", view.requestCount == 0
                    ? 0 : round2(view.sumMillis / (double) view.requestCount));
            Map<String, Long> instances = new LinkedHashMap<>();
            for (Map.Entry<String, LongAdder> entry : instanceCounts.entrySet()) {
                instances.put(entry.getKey(), entry.getValue().sum());
            }
            row.put("instances", instances);
            // 版本归属：host:port -> group，供前端在实例旁标版本
            Map<String, String> groups = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : instanceGroups.entrySet()) {
                groups.put(entry.getKey(), entry.getValue());
            }
            row.put("instanceGroups", groups);
            return row;
        }
    }

    /** 上游维度统计：按 host:port 记录响应时间、连接失败、超时。 */
    static final class UpstreamMetrics {
        final LongAdder requests = new LongAdder();
        final LongAdder sumMillis = new LongAdder();
        final LongAdder connectFail = new LongAdder();
        final LongAdder timeout = new LongAdder();
        final TimeRing ring = new TimeRing(RING_SECONDS);

        void record(long epochSecond, long costMillis, boolean connectFailFlag, boolean timeoutFlag) {
            requests.increment();
            sumMillis.add(Math.max(0, costMillis));
            if (connectFailFlag) {
                connectFail.increment();
            }
            if (timeoutFlag) {
                timeout.increment();
            }
            int status = connectFailFlag || timeoutFlag ? 502 : 200;
            ring.record(epochSecond, Math.max(0, costMillis), connectFailFlag || timeoutFlag, status);
        }

        /**
         * 路由 × 实例维度专用：按真实 statusCode 归类（而非只归 200/502），
         * 并把连接失败/超时一并写入按秒窗口，使二者可给出窗口口径。
         */
        void record(long epochSecond, long costMillis, int statusCode,
                    boolean connectFailFlag, boolean timeoutFlag) {
            requests.increment();
            sumMillis.add(Math.max(0, costMillis));
            if (connectFailFlag) {
                connectFail.increment();
            }
            if (timeoutFlag) {
                timeout.increment();
            }
            ring.record(epochSecond, Math.max(0, costMillis), statusCode >= 500, statusCode,
                    connectFailFlag, timeoutFlag);
        }

        long windowRequestCount(long nowSecond, int windowSeconds) {
            return ring.view(nowSecond, windowSeconds).requestCount;
        }

        /**
         * 路由 × 实例的窗口观测：全部字段取窗口口径，
         * errorRate 的分子是窗口内 5xx（连接失败/超时按 502 计入 5xx）。
         */
        Map<String, Object> windowSnapshot(String routeId, String hostPort, long nowSecond, int windowSeconds) {
            TimeRing.WindowView view = ring.view(nowSecond, windowSeconds);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("routeId", routeId);
            row.put("hostPort", hostPort);
            row.put("windowRequests", view.requestCount);
            row.put("errorRate", view.requestCount == 0
                    ? 0 : round2(view.status5xx / (double) view.requestCount));
            row.put("avgMillis", view.requestCount == 0
                    ? 0 : round2(view.sumMillis / (double) view.requestCount));
            row.put("p95Millis", percentile(view.samples, 0.95));
            row.put("p95Samples", view.samples.length);
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("2xx", view.status2xx);
            status.put("3xx", view.status3xx);
            status.put("4xx", view.status4xx);
            status.put("5xx", view.status5xx);
            row.put("status", status);
            row.put("connectFail", view.connectFail);
            row.put("timeout", view.timeout);
            return row;
        }

        Map<String, Object> snapshot(String hostPort, long nowSecond, int windowSeconds) {
            TimeRing.WindowView view = ring.view(nowSecond, windowSeconds);
            Map<String, Object> row = new LinkedHashMap<>();
            long count = requests.sum();
            row.put("hostPort", hostPort);
            row.put("requests", count);
            row.put("lastSecondRequests", ring.countAt(nowSecond - 1));
            row.put("windowRequests", view.requestCount);
            row.put("avgMillis", view.requestCount == 0
                    ? 0 : round2(view.sumMillis / (double) view.requestCount));
            row.put("connectFail", connectFail.sum());
            row.put("timeout", timeout.sum());
            return row;
        }
    }
}
