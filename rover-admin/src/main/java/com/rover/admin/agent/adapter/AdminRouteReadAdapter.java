package com.rover.admin.agent.adapter;

import com.rover.admin.service.AdminConfigService;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.SnapshotUnavailableException;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.RouteSnapshot;
import java.util.ArrayList;
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
                serviceNameOf(route), groupsOf(route),
                AdminValues.text(route.get("targetUrl")), observedAt);
    }

    /** 版本目标的 serviceName（校验保证同一条路由的 targets 同服务）；静态路由或空列表时为空串。 */
    private static String serviceNameOf(Map<String, Object> route) {
        return AdminValues.text(firstTarget(route).get("serviceName"));
    }

    /** 版本分组：单版本时就是该 group，多版本时用逗号连接，让只读视图不会漏掉某个版本。 */
    private static String groupsOf(Map<String, Object> route) {
        List<String> groups = new ArrayList<>();
        for (Object item : targets(route)) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String group = AdminValues.text(map.get("group"));
            if (!group.isBlank() && !groups.contains(group)) {
                groups.add(group);
            }
        }
        return String.join(",", groups);
    }

    private static Map<?, ?> firstTarget(Map<String, Object> route) {
        List<?> targets = targets(route);
        return targets.isEmpty() || !(targets.get(0) instanceof Map<?, ?> map) ? Map.of() : map;
    }

    private static List<?> targets(Map<String, Object> route) {
        Object value = route.get("targets");
        return value instanceof List<?> list ? list : List.of();
    }
}