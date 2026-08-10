package com.rover.nameserver.core.runtime;

import com.rover.nameserver.core.config.NameserverRuntimeConfigManager;
import com.rover.nameserver.core.health.HealthChecker;
import com.rover.nameserver.core.push.PushService;
import com.rover.nameserver.core.registry.ServiceRegistry;
import com.rover.nameserver.core.server.NameserverServerOptions;
import lombok.Getter;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:45:00
 * Description: Nameserver 运行时，供管理口和热更新使用
 */
@Getter
public class NameserverRuntime {

    private final NameserverServerOptions options;
    private final ServiceRegistry registry;
    private final PushService pushService;
    private final HealthChecker healthChecker;
    private final NameserverRuntimeConfigManager configManager;

    public NameserverRuntime(
            NameserverServerOptions options,
            ServiceRegistry registry,
            PushService pushService,
            HealthChecker healthChecker,
            NameserverRuntimeConfigManager configManager) {
        this.options = options;
        this.registry = registry;
        this.pushService = pushService;
        this.healthChecker = healthChecker;
        this.configManager = configManager;
    }
}
