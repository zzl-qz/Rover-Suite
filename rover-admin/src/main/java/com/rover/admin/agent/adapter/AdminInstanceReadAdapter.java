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

    /**
     * 逐字段搬运，不做推断：字段缺失时按 {@link AdminValues} 的统一约定收敛（空串 / 0 / false）。
     * 这里必须保留 {@code instanceId}——它是「指向某个具体实例」的唯一稳定标识，
     * 丢掉之后 Agent 只能看到「有一台实例有问题」，指不到操作对象。
     */
    private static InstanceSnapshot toSnapshot(Map<String, Object> instance) {
        return new InstanceSnapshot(
                AdminValues.text(instance.get("serviceName")),
                AdminValues.text(instance.get("group")),
                AdminValues.text(instance.get("instanceId")),
                AdminValues.text(instance.get("host")),
                AdminValues.intValue(instance.get("port")),
                AdminValues.boolValue(instance.get("healthy")),
                AdminValues.intValue(instance.get("weight")),
                AdminValues.boolValue(instance.get("ephemeral")),
                AdminValues.longValue(instance.get("lastHeartbeatMillis")));
    }
}
