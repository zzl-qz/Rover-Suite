package com.rover.admin.agent.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.rover.admin.service.AdminConfigService;
import com.rover.agent.core.port.SnapshotUnavailableException;
import com.rover.agent.core.port.TraceReadPort;
import com.rover.agent.core.snapshot.TraceRow;
import com.rover.agent.core.snapshot.TraceSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 从 Admin 管理口读取 Gateway 请求追踪，并只保留与目标路径精确匹配的记录。 */
@Component
public class AdminTraceReadAdapter implements TraceReadPort {

    private final AdminConfigService admin;

    public AdminTraceReadAdapter(AdminConfigService admin) {
        this.admin = admin;
    }

    @Override
    public TraceSnapshot byPath(String path) {
        String target = path == null ? "" : path.trim();
        try {
            JsonNode traces = admin.loadTraces(Map.of("path", target));
            List<TraceRow> rows = new ArrayList<>();
            for (JsonNode row : traces.path("traces")) {
                String rowPath = row.path("path").asText("");
                if (!target.equals(rowPath)) {
                    continue;
                }
                rows.add(new TraceRow(row.path("traceId").asText(""), rowPath,
                        row.path("statusCode").asInt(0), row.path("startMillis").asLong(0)));
            }
            boolean disabled = traces.path("enabled").isBoolean() && !traces.path("enabled").asBoolean();
            return new TraceSnapshot(!disabled, traces.path("sampleRate").asDouble(0), List.copyOf(rows),
                    System.currentTimeMillis());
        } catch (Exception ex) {
            throw new SnapshotUnavailableException("读取 Gateway 追踪失败", ex);
        }
    }
}