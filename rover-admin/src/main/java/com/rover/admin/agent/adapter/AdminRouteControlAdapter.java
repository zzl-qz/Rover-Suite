package com.rover.admin.agent.adapter;

import com.rover.admin.client.ManageApiCallException;
import com.rover.admin.service.AdminConfigService;
import com.rover.agent.core.port.RouteChangePreview;
import com.rover.agent.core.port.RouteChangeResult;
import com.rover.agent.core.port.RouteControlException;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.core.port.RouteControlRoute;
import com.rover.agent.core.port.RouteControlState;
import com.rover.agent.core.port.RouteControlTarget;
import com.rover.agent.core.port.RouteOperation;
import com.rover.agent.core.port.RouteOperationStatus;
import com.rover.agent.core.port.SnapshotUnavailableException;
import com.rover.common.constants.ManageApiPaths;
import com.rover.common.json.JsonCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 通过 Admin 管理口实现 {@link RouteControlPort}，转换路由数据与异常类型。 */
@Component
public class AdminRouteControlAdapter implements RouteControlPort {

    private static final String FIELD_TARGETS = "targets";

    private final AdminConfigService admin;

    public AdminRouteControlAdapter(AdminConfigService admin) {
        this.admin = admin;
    }

    @Override
    public RouteControlState state() {
        Map<String, Object> payload;
        try {
            payload = admin.routesState();
        } catch (RuntimeException ex) {
            throw new SnapshotUnavailableException("读取 Gateway 路由表失败: " + messageOf(ex), ex);
        }
        List<RouteControlRoute> routes = new ArrayList<>();
        for (Object row : listOf(payload.get("routes"))) {
            if (row instanceof Map<?, ?> map) {
                routes.add(toRoute(map));
            }
        }
        return new RouteControlState(AdminValues.intValue(payload.get(ManageApiPaths.PARAM_REVISION)),
                AdminValues.text(payload.get("appliedOperationId")), routes);
    }

    @Override
    public RouteChangePreview preview(String routeId, String serviceName, String group, int weight) {
        Map<String, Object> payload = readRoutes();
        List<Map<String, Object>> routes = mutableRows(payload);
        int index = indexOfRoute(routes, routeId);
        if (index < 0) {
            throw new RouteControlException(RouteControlException.Kind.REJECTED, "路由不存在: " + routeId);
        }
        Map<String, Object> candidate = new LinkedHashMap<>(routes.get(index));
        candidate.put(FIELD_TARGETS, rewriteWeight(candidate.get(FIELD_TARGETS), serviceName, group, weight));
        List<Map<String, Object>> merged = new ArrayList<>(routes);
        merged.set(index, candidate);
        try {
            Map<String, Object> result = admin.previewRoutes(merged);
            return new RouteChangePreview(AdminValues.intValue(result.get(ManageApiPaths.PARAM_REVISION)),
                    AdminValues.text(result.get("message")), changeLines(result.get("changes")));
        } catch (RuntimeException ex) {
            throw translate("预览路由变更", ex);
        }
    }

    @Override
    public RouteChangeResult adjustTargetWeight(String routeId, String serviceName, String group, int weight,
                                                int expectedRevision, String operationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(ManageApiPaths.PARAM_ROUTE_ID, routeId);
        body.put("serviceName", serviceName);
        body.put("group", group);
        body.put("weight", weight);
        body.put(ManageApiPaths.PARAM_REVISION, expectedRevision);
        body.put(ManageApiPaths.PARAM_OPERATION_ID, operationId);
        try {
            Map<String, Object> result = admin.adjustTargetWeight(body);
            return new RouteChangeResult(AdminValues.text(result.get("status")),
                    AdminValues.intValue(result.get(ManageApiPaths.PARAM_REVISION)),
                    AdminValues.text(result.get(ManageApiPaths.PARAM_OPERATION_ID)),
                    AdminValues.text(result.get("message")));
        } catch (RuntimeException ex) {
            throw translate("调整版本权重", ex);
        }
    }

    @Override
    public RouteOperation operation(String operationId) {
        try {
            Map<String, Object> result = admin.routeOperation(operationId);
            return new RouteOperation(operationId, RouteOperationStatus.from(AdminValues.text(result.get("status"))),
                    AdminValues.intValue(result.get(ManageApiPaths.PARAM_REVISION)),
                    AdminValues.text(result.get("message")),
                    AdminValues.intValue(result.get("currentRevision")));
        } catch (RuntimeException ex) {
            throw translate("回查操作结果", ex);
        }
    }

    // ---------------------------------------------------------------- 结构与失败翻译

    private Map<String, Object> readRoutes() {
        try {
            return admin.routesState();
        } catch (RuntimeException ex) {
            throw translate("读取 Gateway 路由表", ex);
        }
    }

    private static RouteControlRoute toRoute(Map<?, ?> row) {
        List<RouteControlTarget> targets = new ArrayList<>();
        for (Object item : listOf(row.get(FIELD_TARGETS))) {
            if (item instanceof Map<?, ?> target) {
                targets.add(new RouteControlTarget(AdminValues.text(target.get("serviceName")),
                        AdminValues.text(target.get("group")), AdminValues.intValue(target.get("weight"))));
            }
        }
        return new RouteControlRoute(AdminValues.text(row.get("id")), AdminValues.text(row.get("businessPrefix")),
                AdminValues.text(row.get("targetUrl")), targets);
    }

    /** 路由行的可变副本：预览要拼候选整表，不能直接改管理口返回的对象。 */
    private static List<Map<String, Object>> mutableRows(Map<String, Object> payload) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object row : listOf(payload.get("routes"))) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            rows.add(copy);
        }
        return rows;
    }

    private static int indexOfRoute(List<Map<String, Object>> routes, String routeId) {
        String wanted = AdminValues.text(routeId);
        for (int index = 0; index < routes.size(); index++) {
            Map<String, Object> row = routes.get(index);
            if (wanted.equals(AdminValues.text(row.get("id")))
                    || wanted.equals(AdminValues.text(row.get("businessPrefix")))) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 只改指定版本的权重，其余目标原样保留——预览的候选表因此与之后真正提交的表一致，
     * 「预览看到的差异」不会比「执行造成的改动」多出任何东西。
     */
    private static List<Map<String, Object>> rewriteWeight(Object targets, String serviceName, String group,
                                                           int weight) {
        String wantedService = AdminValues.text(serviceName);
        String wantedGroup = AdminValues.text(group);
        List<Map<String, Object>> rewritten = new ArrayList<>();
        boolean matched = false;
        for (Object item : listOf(targets)) {
            if (!(item instanceof Map<?, ?> target)) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            target.forEach((key, value) -> copy.put(String.valueOf(key), value));
            if (wantedService.equals(AdminValues.text(target.get("serviceName")))
                    && wantedGroup.equals(AdminValues.text(target.get("group")))) {
                copy.put("weight", weight);
                matched = true;
            }
            rewritten.add(copy);
        }
        if (!matched) {
            throw new RouteControlException(RouteControlException.Kind.REJECTED,
                    "路由上没有该版本目标: " + wantedService + "@" + wantedGroup);
        }
        return rewritten;
    }

    /** 网关的差异行 → 人类可读的一行文本，直接进变更卡片的预览区。 */
    private static List<String> changeLines(Object changes) {
        List<String> lines = new ArrayList<>();
        for (Object item : listOf(changes)) {
            if (!(item instanceof Map<?, ?> change)) {
                continue;
            }
            String where = AdminValues.text(change.get("businessPrefix"));
            if (where.isEmpty()) {
                where = AdminValues.text(change.get("routeId"));
            }
            String detail = AdminValues.text(change.get("detail"));
            lines.add(AdminValues.text(change.get("kind")) + " " + where + (detail.isEmpty() ? "" : "：" + detail));
        }
        return lines;
    }

    /** 映射管理口异常：409 为版本冲突，其他 4xx 为请求拒绝，5xx 为执行失败，0 为响应未知。 */
    private static RouteControlException translate(String action, RuntimeException ex) {
        if (ex instanceof RouteControlException control) {
            return control;
        }
        String message = action + "失败：" + messageOf(ex);
        if (ex instanceof ManageApiCallException manage) {
            return switch (manage.statusCode()) {
                case 0 -> new RouteControlException(RouteControlException.Kind.UNAVAILABLE, message, ex);
                case 409 -> new RouteControlException(RouteControlException.Kind.CONFLICT, message);
                default -> manage.statusCode() >= 500
                        ? new RouteControlException(RouteControlException.Kind.FAILED, message, ex)
                        : new RouteControlException(RouteControlException.Kind.REJECTED, message, ex);
            };
        }
        // 没拿到明确响应（解析失败、连接中断等）：按「结果未知」处理，让上层用原号回查。
        return new RouteControlException(RouteControlException.Kind.UNAVAILABLE, message, ex);
    }

    private static List<?> listOf(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String messageOf(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
