package com.rover.admin.agent.adapter;

import com.rover.admin.service.AdminConfigService;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.SnapshotUnavailableException;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 从 Admin 管理口读取 Nameserver 注册实例。 */
@Component
public class AdminInstanceReadAdapter implements InstanceReadPort {

    private final AdminConfigService admin;

    public AdminInstanceReadAdapter(AdminConfigService admin) {
        this.admin = admin;
    }

    @Override
    public List<InstanceSnapshot> instances() {
        try {
            return admin.listInstances().stream().map(AdminInstanceReadAdapter::toSnapshot).toList();
        } catch (Exception ex) {
            throw new SnapshotUnavailableException("读取 Nameserver 实例失败", ex);
        }
    }

    private static InstanceSnapshot toSnapshot(Map<String, Object> instance) {
        return new InstanceSnapshot(AdminValues.text(instance.get("serviceName")),
                AdminValues.text(instance.get("group")), AdminValues.text(instance.get("host")),
                AdminValues.intValue(instance.get("port")), Boolean.TRUE.equals(instance.get("healthy")));
    }
}