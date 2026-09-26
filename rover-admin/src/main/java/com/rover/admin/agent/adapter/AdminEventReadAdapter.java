package com.rover.admin.agent.adapter;

import com.rover.admin.service.AdminConfigService;
import com.rover.agent.core.port.EventReadPort;
import com.rover.agent.core.port.SnapshotUnavailableException;
import com.rover.agent.core.snapshot.RegistryEventSnapshot;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 从 Admin 管理口读取 Nameserver 注册事件（注册、注销、剔除、标记不健康、推送）。 */
@Component
public class AdminEventReadAdapter implements EventReadPort {

    private final AdminConfigService admin;

    public AdminEventReadAdapter(AdminConfigService admin) {
        this.admin = admin;
    }

    @Override
    public List<RegistryEventSnapshot> events() {
        try {
            return admin.loadEvents().stream().map(AdminEventReadAdapter::toSnapshot).toList();
        } catch (Exception ex) {
            throw new SnapshotUnavailableException("读取注册事件失败", ex);
        }
    }

    private static RegistryEventSnapshot toSnapshot(Map<String, Object> event) {
        return new RegistryEventSnapshot(
                AdminValues.longValue(event.get("timestampMillis")),
                AdminValues.text(event.get("type")),
                AdminValues.text(event.get("serviceName")),
                AdminValues.text(event.get("instanceId")),
                AdminValues.text(event.get("detail")));
    }
}
