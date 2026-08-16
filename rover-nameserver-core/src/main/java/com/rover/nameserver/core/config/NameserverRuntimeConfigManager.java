package com.rover.nameserver.core.config;

import com.rover.common.config.AbstractRuntimeConfigManager;
import com.rover.common.config.ConfigChangeEvent;
import com.rover.common.config.RuntimeConfigOverlayStore;
import lombok.Getter;

import java.nio.file.Path;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:30:00
 * Description: Nameserver 侧运行时配置管理器：注册配置项、overlay 持久化恢复并将变更实时应用到运行时
 */
@Getter
public class NameserverRuntimeConfigManager extends AbstractRuntimeConfigManager {

    /** overlay 落盘路径：工作目录下 config/nameserver-runtime.overlay.json */
    public static final Path DEFAULT_OVERLAY = Path.of("config", "nameserver-runtime.overlay.json");

    /** 配置变更应用到 Nameserver 运行时的桥接器 */
    private final NameserverRuntimeConfigApplier applier;

    /** 使用默认 applier 和 overlay 路径构造。 */
    public NameserverRuntimeConfigManager() {
        this(new NameserverRuntimeConfigApplier());
    }

    /** 使用指定 applier（便于测试注入）。 */
    public NameserverRuntimeConfigManager(NameserverRuntimeConfigApplier applier) {
        this(applier, new RuntimeConfigOverlayStore(DEFAULT_OVERLAY));
    }

    /** 完整构造：注入 applier 与 overlay 存储，并注册内置配置项。 */
    public NameserverRuntimeConfigManager(
            NameserverRuntimeConfigApplier applier, RuntimeConfigOverlayStore overlayStore) {
        super(overlayStore, "Nameserver");
        this.applier = applier;
        addConfig("nameserver.health.checkIntervalMillis", "5000", "5000", "注册中心健康检查间隔（毫秒）",
                List.of("3000", "5000", "10000"));
        addConfig("nameserver.heartbeat.timeoutMillis", "15000", "15000", "注册中心心跳超时时间（毫秒）",
                List.of("10000", "15000", "30000"));
        addConfig("nameserver.instance.expireMillis", "30000", "30000", "注册中心实例过期时间（毫秒）",
                List.of("30000", "60000", "90000"));
        addConfig("nameserver.push.enabled", "true", "true", "注册中心服务变更推送开关",
                List.of("true", "false"));
    }

    @Override
    protected void applyChange(ConfigChangeEvent event) {
        applier.apply(event);
    }

    @Override
    protected void validate(String key, String value) {
        if (key.endsWith("Millis")) {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(key + " 必须大于 0");
            }
        }
        if ("nameserver.push.enabled".equals(key)
                && !"true".equalsIgnoreCase(value)
                && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("push.enabled 仅支持 true/false");
        }
    }

    @Override
    protected String normalize(String key, String value) {
        if ("nameserver.push.enabled".equals(key)) {
            return Boolean.parseBoolean(value) ? "true" : "false";
        }
        return value;
    }
}
