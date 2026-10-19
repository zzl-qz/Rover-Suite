package com.rover.admin.agent.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.rover.admin.service.AdminConfigService;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.SnapshotUnavailableException;
import com.rover.agent.core.snapshot.GatewayMetricSnapshot;
import com.rover.agent.core.snapshot.RouteUpstreamSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 从 Admin 管理口读取 Gateway 全局指标与「路由 × 上游实例」窗口观测。 */
@Component
public class AdminMetricReadAdapter implements MetricReadPort {

    private final AdminConfigService admin;

    public AdminMetricReadAdapter(AdminConfigService admin) {
        this.admin = admin;
    }

    @Override
    public com.rover.agent.core.snapshot.RouteWindowSnapshot routeWindow(String routeId, int windowSeconds) {
        try { return decodeWindow(routeId, admin.loadRouteUpstreams(routeId, windowSeconds)); }
        catch (SnapshotUnavailableException ex) { throw ex; }
        catch (Exception ex) { throw AdminValues.unavailable("读取原生最终响应窗口失败", ex); }
    }

    @Override
    public com.rover.agent.core.snapshot.RouteWindowSnapshot routeWindow(String routeId, com.rover.agent.core.model.TimeRange range) {
        try { return decodeWindow(routeId, admin.loadRouteWindow(routeId, range.fromMillis(), range.toMillis())); }
        catch (SnapshotUnavailableException ex) { throw ex; }
        catch (Exception ex) { throw AdminValues.unavailable("读取历史最终响应窗口失败", ex); }
    }

    private static com.rover.agent.core.snapshot.RouteWindowSnapshot decodeWindow(String routeId, JsonNode payload) {
        try {
            JsonNode window = payload.path("routeWindow");
            if (!payload.path("enabled").asBoolean(true) || window.path("schemaVersion").asInt() != 1
                    || !routeId.equals(window.path("routeId").asText())
                    || !window.path("finalRequests").canConvertToLong() || !window.path("final5xx").canConvertToLong()) {
                throw new SnapshotUnavailableException("Gateway 未提供有效的原生最终响应窗口，不能用旧上游统计替代");
            }
            long total = window.path("finalRequests").asLong(), errors = window.path("final5xx").asLong();
            if (total < 0 || errors < 0 || errors > total) { throw new IllegalArgumentException("窗口计数不一致"); }
            for (String key : List.of("finalRequests", "final5xx", "upstream5xx", "gateway5xx", "incompleteResponses", "attemptCount", "latencySamples")) {
                if (!window.path(key).isIntegralNumber() || !window.path(key).canConvertToLong() || window.path(key).asLong() < 0) {
                    throw new IllegalArgumentException("窗口缺失有效计数：" + key);
                }
            }
            if (window.path("upstream5xx").asLong() > errors - window.path("gateway5xx").asLong()
                    || window.path("incompleteResponses").asLong() > total
                    || window.path("latencySamples").asLong() != total
                    || !"ALL_FINAL_REQUESTS".equals(window.path("samplingMode").asText())
                    || !"HISTOGRAM_UPPER_BOUND".equals(window.path("p95Method").asText())) {
                throw new IllegalArgumentException("窗口来源或计数口径不一致");
            }
            JsonNode buckets = window.path("latencyBucketCounts"), bounds = window.path("latencyBucketUpperBoundsMillis");
            if (!buckets.isArray() || buckets.isEmpty() || buckets.size() != bounds.size()) { throw new IllegalArgumentException("窗口直方图缺失"); }
            long samples = 0, previous = -1;
            for (int index = 0; index < buckets.size(); index++) {
                if (!buckets.get(index).isIntegralNumber() || buckets.get(index).asLong() < 0
                        || !bounds.get(index).isIntegralNumber() || bounds.get(index).asLong() <= previous) {
                    throw new IllegalArgumentException("窗口直方图无效");
                }
                samples = Math.addExact(samples, buckets.get(index).asLong()); previous = bounds.get(index).asLong();
            }
            if (samples != total) { throw new IllegalArgumentException("直方图样本与请求数不一致"); }
            var requested = new com.rover.agent.core.model.TimeRange(window.required("requestedFromMillis").asLong(),
                    window.required("requestedToMillis").asLong());
            var coverage = window.path("coverageFromMillis").isNull() ? null
                    : new com.rover.agent.core.model.TimeRange(window.required("coverageFromMillis").asLong(),
                            window.required("coverageToMillis").asLong());
            long observed = window.required("observedAtMillis").asLong();
            if (coverage != null && (coverage.fromMillis() < requested.fromMillis() || coverage.toMillis() > requested.toMillis()
                    || observed < coverage.toMillis())) { throw new IllegalArgumentException("窗口覆盖不一致"); }
            var quality = com.rover.agent.core.model.EvidenceQuality.valueOf(window.required("qualityStatus").asText());
            if (coverage == null && quality != com.rover.agent.core.model.EvidenceQuality.OUT_OF_RANGE) {
                throw new IllegalArgumentException("缺失覆盖范围不能声明数据可用");
            }
            if (quality == com.rover.agent.core.model.EvidenceQuality.EMPTY && total != 0
                    || quality == com.rover.agent.core.model.EvidenceQuality.OUT_OF_RANGE && (total != 0 || coverage != null)
                    || quality == com.rover.agent.core.model.EvidenceQuality.AVAILABLE && !requested.equals(coverage)) {
                throw new IllegalArgumentException("窗口质量声明与覆盖不一致");
            }
            List<String> limitations = new ArrayList<>();
            window.path("limitations").forEach(item -> limitations.add(item.asText()));
            Map<String, Object> facts = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(window,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            return new com.rover.agent.core.snapshot.RouteWindowSnapshot(routeId, requested, coverage, observed,
                    quality, total, errors, window.required("samplingMode").asText(), facts, List.copyOf(limitations));
        } catch (SnapshotUnavailableException ex) { throw ex; }
        catch (Exception ex) { throw AdminValues.unavailable("读取原生最终响应窗口失败", ex); }
    }

    @Override
    public GatewayMetricSnapshot gatewayWindow(int windowSeconds) {
        try {
            JsonNode metrics = admin.loadMetrics();
            if (!metrics.path("enabled").asBoolean(false)) {
                throw new SnapshotUnavailableException("Gateway 指标采集未启用或状态未知");
            }
            Map<String, Object> snapshot = admin.loadLive(windowSeconds);
            JsonNode live = (JsonNode) snapshot.get("gateway");
            if (live == null || live.has("error")) {
                throw new SnapshotUnavailableException("Gateway live 数据不可用");
            }
            return new GatewayMetricSnapshot(
                    live.path("traffic").path("windowRequests").asLong(-1),
                    live.path("traffic").path("status").path("5xx").asLong(-1),
                    live.path("resources").path("rejects").path("noUpstream").asLong(-1),
                    System.currentTimeMillis());
        } catch (SnapshotUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw AdminValues.unavailable("读取 Gateway 指标失败", ex);
        }
    }

    /**
     * 读取路由的上游窗口观测；未知路由或无转发记录返回空列表。
     * 指标未启用或端点不可达时抛 {@link SnapshotUnavailableException}。
     */
    @Override
    public List<RouteUpstreamSnapshot> routeUpstreams(String routeId, int windowSeconds) {
        try {
            JsonNode payload = admin.loadRouteUpstreams(routeId, windowSeconds);
            if (!payload.path("enabled").asBoolean(true)) {
                throw new SnapshotUnavailableException("Gateway 指标采集未启用，无法读取按上游实例指标");
            }
            int window = payload.path("windowSeconds").asInt(windowSeconds);
            long observedAt = payload.path("observedAtMillis").asLong(System.currentTimeMillis());
            List<RouteUpstreamSnapshot> rows = new ArrayList<>();
            for (JsonNode row : payload.path("rows")) {
                // group 是版本归属：网关在每条实例行上都带了它（空串 = 无版本 / 默认组）。
                // 之前没有读，导致 Agent 只能说「某台机器有问题」而无法归因到具体版本。
                rows.add(new RouteUpstreamSnapshot(
                        row.path("routeId").asText(routeId == null ? "" : routeId),
                        row.path("hostPort").asText(""),
                        row.path("groupKnown").asBoolean(true) ? row.path("group").asText(RouteUpstreamSnapshot.NO_GROUP)
                                : "未知（旧接口按端点混合了多个版本）",
                        window,
                        row.path("windowRequests").asLong(0),
                        row.path("status").path("5xx").asLong(0),
                        row.path("connectFail").asLong(0),
                        row.path("timeout").asLong(0),
                        row.path("avgMillis").asDouble(0),
                        row.path("p95Millis").asLong(0),
                        observedAt));
            }
            return rows;
        } catch (SnapshotUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw AdminValues.unavailable("读取 Gateway 按上游实例指标失败", ex);
        }
    }
}
