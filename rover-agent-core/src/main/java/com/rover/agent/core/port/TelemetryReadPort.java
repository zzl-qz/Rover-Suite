package com.rover.agent.core.port;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.InvestigationContext;
import java.util.List;
import java.util.Set;

/** 外部只读遥测；宿主固定数据源和查询模板，不接受模型提供 URL 或查询语句。 */
public interface TelemetryReadPort {
    Set<AgentCapability> capabilities();
    List<Evidence> query(AgentCapability capability, InvestigationContext context);

    static TelemetryReadPort disconnected() {
        return new TelemetryReadPort() {
            public Set<AgentCapability> capabilities() { return Set.of(); }
            public List<Evidence> query(AgentCapability capability, InvestigationContext context) {
                throw new SnapshotUnavailableException("外部遥测数据源未接入");
            }
        };
    }
}
