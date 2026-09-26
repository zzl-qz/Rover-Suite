package com.rover.admin.agent.adapter;

import com.rover.admin.service.AdminConfigService;
import com.rover.agent.core.port.ConfigReadPort;
import com.rover.agent.core.port.SnapshotUnavailableException;
import com.rover.agent.core.snapshot.ConfigEntrySnapshot;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 从 Admin 管理口读取 Gateway / Nameserver 当前生效配置。 */
@Component
public class AdminConfigReadAdapter implements ConfigReadPort {

    private final AdminConfigService admin;

    public AdminConfigReadAdapter(AdminConfigService admin) {
        this.admin = admin;
    }

    @Override
    public List<ConfigEntrySnapshot> configs() {
        try {
            return admin.listConfigs().stream().map(AdminConfigReadAdapter::toSnapshot).toList();
        } catch (Exception ex) {
            throw new SnapshotUnavailableException("读取生效配置失败", ex);
        }
    }

    /**
     * 单条配置的宽松映射：管理口用 {@code error=true} 标注「该组件配置读取失败」的占位条目，
     * 这里必须把它记成 unavailable，否则读取失败会被当成「当前值就是空」。
     */
    private static ConfigEntrySnapshot toSnapshot(Map<String, Object> item) {
        return new ConfigEntrySnapshot(
                AdminValues.text(item.get("component")),
                AdminValues.text(item.get("key")),
                AdminValues.text(item.get("description")),
                AdminValues.text(item.get("value")),
                AdminValues.text(item.get("defaultValue")),
                AdminValues.text(item.get("applyMode")),
                AdminValues.boolValue(item.get("hotReloadable")),
                AdminValues.boolValue(item.get("error")));
    }
}
