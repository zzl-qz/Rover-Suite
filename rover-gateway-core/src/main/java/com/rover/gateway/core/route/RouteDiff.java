package com.rover.gateway.core.route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Author: Daylight
 * Created: 2026-09-27 14:45:00
 * Description: 路由表差异比对，供管理口「预览变更」使用
 *
 * <p>比对以字段表（{@link RouteOverlayStore#toRow}）为准，因此预览里看到的就是将来会落盘的内容，
 * 不会出现「预览说改了 targets、实际改的是别处」这种不一致。
 */
public final class RouteDiff {

    /** 差异类型。 */
    public static final String ADDED = "ADDED";
    public static final String REMOVED = "REMOVED";
    public static final String MODIFIED = "MODIFIED";

    private RouteDiff() {
    }

    /**
     * 一条路由的差异。
     *
     * @param kind           类型：ADDED / REMOVED / MODIFIED
     * @param routeId        路由 ID，可能为空
     * @param businessPrefix 业务前缀，用来在路由 ID 为空时定位
     * @param detail         逐字段的「旧 → 新」说明；新增/删除时为空串
     */
    public record Change(String kind, String routeId, String businessPrefix, String detail) { }

    /**
     * 比对当前路由表与候选路由表。
     *
     * @param current   当前生效的路由表
     * @param candidate 候选路由表（建议先经过校验，得到的就是将来生效的内容）
     * @return 差异列表；两侧完全一致时为空
     */
    public static List<Change> between(List<RouteConfig> current, List<RouteConfig> candidate) {
        Map<String, RouteConfig> before = index(current);
        Map<String, RouteConfig> after = index(candidate);
        List<Change> changes = new ArrayList<>();

        for (Map.Entry<String, RouteConfig> entry : before.entrySet()) {
            if (!after.containsKey(entry.getKey())) {
                changes.add(describe(REMOVED, entry.getValue(), ""));
            }
        }
        for (Map.Entry<String, RouteConfig> entry : after.entrySet()) {
            RouteConfig next = entry.getValue();
            RouteConfig previous = before.get(entry.getKey());
            if (previous == null) {
                changes.add(describe(ADDED, next, ""));
                continue;
            }
            String detail = fieldDiff(previous, next);
            if (!detail.isEmpty()) {
                changes.add(describe(MODIFIED, next, detail));
            }
        }
        return changes;
    }

    /** 逐字段比对，输出「字段: 旧 → 新」片段。 */
    private static String fieldDiff(RouteConfig before, RouteConfig after) {
        Map<String, Object> oldRow = RouteOverlayStore.toRow(before);
        Map<String, Object> newRow = RouteOverlayStore.toRow(after);
        StringBuilder detail = new StringBuilder();
        for (Map.Entry<String, Object> entry : newRow.entrySet()) {
            Object oldValue = oldRow.get(entry.getKey());
            Object newValue = entry.getValue();
            if (oldValue == null ? newValue == null : oldValue.equals(newValue)) {
                continue;
            }
            if (detail.length() > 0) {
                detail.append("; ");
            }
            detail.append(entry.getKey()).append(": ").append(display(oldValue))
                    .append(" → ").append(display(newValue));
        }
        return detail.toString();
    }

    private static Change describe(String kind, RouteConfig route, String detail) {
        return new Change(kind, nullToEmpty(route.getId()), nullToEmpty(route.getBusinessPrefix()), detail);
    }

    private static Map<String, RouteConfig> index(List<RouteConfig> routes) {
        Map<String, RouteConfig> indexed = new LinkedHashMap<>();
        if (routes == null) {
            return indexed;
        }
        for (RouteConfig route : routes) {
            if (route == null) {
                continue;
            }
            indexed.put(keyOf(route), route);
        }
        return indexed;
    }

    /** 路由标识：id 优先，没有 id 就用业务前缀；校验保证前缀唯一。 */
    private static String keyOf(RouteConfig route) {
        String id = route.getId();
        if (id != null && !id.isBlank()) {
            return "id:" + id;
        }
        return "prefix:" + nullToEmpty(route.getBusinessPrefix());
    }

    private static String display(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
