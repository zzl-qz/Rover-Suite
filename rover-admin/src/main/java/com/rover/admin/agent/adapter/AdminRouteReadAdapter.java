package com.rover.admin.agent.adapter;

import com.rover.admin.service.AdminConfigService;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.SnapshotUnavailableException;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.RouteSnapshot;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 从 Admin 管理口读取 Gateway 路由表与服务发现模式。 */
@Component
public class AdminRouteReadAdapter implements RouteReadPort {

    private final AdminConfigService admin;

    public AdminRouteReadAdapter(AdminConfigService admin) {
        this.admin = admin;
    }

    @Override
    public List<RouteSnapshot> routes() {
        long observedAt = System.currentTimeMillis();
        try {
            return admin.listRoutes().stream().map(route -> toSnapshot(route, observedAt)).toList();
        } catch (Exception ex) {
            throw new SnapshotUnavailableException("读取 Gateway 路由失败", ex);
        }
    }

    @Override
    public DiscoveryMode discoveryMode() {
        return DiscoveryMode.from(admin.discoveryType());
    }

    private static RouteSnapshot toSnapshot(Map<String, Object> route, long observedAt) {
        return new RouteSnapshot(AdminValues.text(route.get("id")), AdminValues.text(route.get("businessPrefix")),
                AdminValues.text(route.get("serviceName")), AdminValues.text(route.get("group")),
                AdminValues.text(route.get("targetUrl")), observedAt);
    }
}