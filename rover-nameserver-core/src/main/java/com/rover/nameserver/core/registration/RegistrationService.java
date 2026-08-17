package com.rover.nameserver.core.registration;

import com.rover.common.protocol.RegisterRequest;
import com.rover.nameserver.core.metrics.NameserverMetricsRegistry;
import com.rover.nameserver.core.push.PushService;
import com.rover.nameserver.core.registry.ServiceRegistry;
import java.util.Objects;

/**
 * Author: Daylight
 * Created: 2026-08-17 00:00:00
 * Description: TCP 与 HTTP 共用的实例生命周期门面，统一注册表变更、指标和快照推送
 */
public class RegistrationService {

    private final ServiceRegistry registry;
    private final PushService pushService;
    private final NameserverMetricsRegistry metrics;

    public RegistrationService(
            ServiceRegistry registry,
            PushService pushService,
            NameserverMetricsRegistry metrics) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.pushService = Objects.requireNonNull(pushService, "pushService");
        this.metrics = metrics == null ? new NameserverMetricsRegistry() : metrics;
    }

    /** 注册或续租实例；同 owner 且实例信息未变化时不 bump revision、不推送。 */
    public RegistrationResult register(RegisterRequest request, RegistrationOwner owner) {
        RegistrationResult result = registry.register(request, owner);
        if (result.isChanged()) {
            metrics.register(request.getServiceName(), request.getInstanceId());
        }
        pushIfChanged(result);
        return result;
    }

    /** 刷新当前 owner 的心跳；旧 owner 不得给新会话续租。 */
    public RegistrationResult heartbeat(
            String serviceName, String instanceId, RegistrationOwner owner) {
        RegistrationResult result = registry.heartbeat(serviceName, instanceId, owner);
        if (result.isAccepted()) {
            metrics.heartbeat(serviceName, instanceId);
        }
        pushIfChanged(result);
        return result;
    }

    /** 仅当前 owner 可以显式注销实例，避免旧连接或旧 HTTP session 误删新实例。 */
    public RegistrationResult unregister(
            String serviceName, String instanceId, RegistrationOwner owner) {
        RegistrationResult result = registry.unregister(serviceName, instanceId, owner);
        if (result.isChanged()) {
            metrics.unregister(serviceName, instanceId);
        }
        pushIfChanged(result);
        return result;
    }

    public ServiceRegistry registry() {
        return registry;
    }

    private void pushIfChanged(RegistrationResult result) {
        if (result.snapshot() != null) {
            pushService.pushSnapshot(result.snapshot());
        }
    }
}
