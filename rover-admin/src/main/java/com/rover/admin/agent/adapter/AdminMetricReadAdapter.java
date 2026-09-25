package com.rover.admin.agent.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.rover.admin.service.AdminConfigService;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.SnapshotUnavailableException;
import com.rover.agent.core.snapshot.GatewayMetricSnapshot;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 从 Admin 管理口读取 Gateway 全局指标快照。 */
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
}