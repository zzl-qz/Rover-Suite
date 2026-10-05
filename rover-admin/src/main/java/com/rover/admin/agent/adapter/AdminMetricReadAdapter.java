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
            throw new SnapshotUnavailableException("读取 Gateway 指标失败", ex);
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
                        row.path("group").asText(RouteUpstreamSnapshot.NO_GROUP),
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
            throw new SnapshotUnavailableException("读取 Gateway 按上游实例指标失败", ex);
        }
    }
}