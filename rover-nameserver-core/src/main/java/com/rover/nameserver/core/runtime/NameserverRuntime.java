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
 *
 * 这个类是什么：Nameserver 各业务组件的聚合根，对外暴露统一访问入口。
 * 核心职责：把 Options、注册表、推送、健康检查、配置管理器打包在一起，
 * 让管理 API 与配置热更新不必逐组件注入。
 * 被谁用：NameserverTcpServer 构造时创建；NameserverManageApi、NameserverRuntimeConfigApplier 持有引用。
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

    /**
     * @param options       服务端运行参数
     * @param registry      注册表
     * @param pushService   推送服务
     * @param healthChecker 健康检查器
     * @param configManager 配置管理器
     */
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
