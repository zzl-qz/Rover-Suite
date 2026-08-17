package com.rover.nameserver.core.runtime;

import com.rover.nameserver.core.config.NameserverRuntimeConfigManager;
import com.rover.nameserver.core.health.HealthChecker;
import com.rover.nameserver.core.metrics.NameserverMetricsRegistry;
import com.rover.nameserver.core.push.PushService;
import com.rover.nameserver.core.registration.RegistrationService;
import com.rover.nameserver.core.registry.ServiceRegistry;
import com.rover.nameserver.core.server.NameserverServerOptions;
import lombok.Getter;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:45:00
 * Description: Nameserver 各业务组件的聚合根，统一暴露访问入口供管理 API 与配置热更新使用
 */
@Getter
public class NameserverRuntime {

    /** 服务端启动参数（端口、ACK 模式等） */
    private final NameserverServerOptions options;
    /** 服务注册表 */
    private final ServiceRegistry registry;
    /** 变更推送服务 */
    private final PushService pushService;
    /** 健康检查器 */
    private final HealthChecker healthChecker;
    /** 运行时配置管理器（含 overlay 持久化） */
    private final NameserverRuntimeConfigManager configManager;
    /** 指标注册表（生命周期计数 + 最近事件 + TCP 连接数） */
    private final NameserverMetricsRegistry metrics;
    /** TCP 与 HTTP 共用的注册生命周期门面。 */
    private final RegistrationService registrationService;

    public NameserverRuntime(
            NameserverServerOptions options,
            ServiceRegistry registry,
            PushService pushService,
            HealthChecker healthChecker,
            NameserverRuntimeConfigManager configManager,
            NameserverMetricsRegistry metrics,
            RegistrationService registrationService) {
        this.options = options;
        this.registry = registry;
        this.pushService = pushService;
        this.healthChecker = healthChecker;
        this.configManager = configManager;
        this.metrics = metrics == null ? new NameserverMetricsRegistry() : metrics;
        this.registrationService = java.util.Objects.requireNonNull(
                registrationService, "registrationService");
    }
}
