package com.rover.common.constants;

/** Gateway、Nameserver 与 Admin 共同使用的管理 API 路径。 */
public final class ManageApiPaths {

    private ManageApiPaths() {
    }

    public static final String PREFIX = "/_manage";
    public static final String HEALTH = PREFIX + "/health";
    public static final String STATUS = PREFIX + "/status";
    public static final String CONFIGS = PREFIX + "/configs";
    public static final String ROUTES = PREFIX + "/routes";
    /** 路由变更预览：只比对差异，不落盘、不生效。 */
    public static final String ROUTES_PREVIEW = ROUTES + "/preview";
    /** 单版本权重调整（放量/停推原语）。 */
    public static final String ROUTES_TARGET_WEIGHT = ROUTES + "/targets/weight";
    /** 回滚到最近某次已应用的快照。 */
    public static final String ROUTES_ROLLBACK = ROUTES + "/rollback";
    /** 操作记录查询前缀：/_manage/routes/operations/{operationId}。 */
    public static final String ROUTES_OPERATIONS = ROUTES + "/operations/";
    public static final String METRICS = PREFIX + "/metrics";
    public static final String METRICS_LIVE = METRICS + "/live";
    /** 路由 × 上游实例的窗口观测（只读）。 */
    public static final String METRICS_ROUTES = METRICS + "/routes";
    public static final String METRICS_SELFCHECK = METRICS + "/selfcheck";
    public static final String PROMETHEUS = PREFIX + "/prometheus";
    public static final String TRACES = PREFIX + "/traces";
    public static final String INSTANCES = PREFIX + "/instances";
    public static final String EVENTS = PREFIX + "/events";

    public static final String PARAM_ID = "id";
    public static final String PARAM_BUSINESS_PREFIX = "businessPrefix";
    public static final String PARAM_TRACE_ID = "traceId";
    public static final String PARAM_PATH = "path";
    public static final String PARAM_SLOW = "slow";
    /** 只取错误链路（statusCode >= 500，即 5xx 服务端/上游错误）。 */
    public static final String PARAM_ERROR = "error";
    public static final String PARAM_RANGE = "range";
    public static final String PARAM_ROUTE_ID = "routeId";
    public static final String PARAM_REVISION = "revision";
    public static final String PARAM_OPERATION_ID = "operationId";
    /** 服务发现已观察快照（只读）：网关报告自己看到了哪些服务与版本。 */
    public static final String DISCOVERY_SNAPSHOT = PREFIX + "/discovery/snapshot";
    /** 注册中心实例快照（只读）：带 revision/epoch 的可核对视图。 */
    public static final String INSTANCES_SNAPSHOT = PREFIX + "/instances/snapshot";

    /** live 默认近窗：1 分钟。 */
    public static final int LIVE_RANGE_1M = 60;
    /** live 长窗：5 分钟，也是环形数组上限。 */
    public static final int LIVE_RANGE_5M = 300;

    /** 只认 60 / 300，其他值按靠近哪档收。 */
    public static int clampLiveRange(String raw) {
        if (raw == null || raw.isBlank()) {
            return LIVE_RANGE_1M;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > LIVE_RANGE_1M ? LIVE_RANGE_5M : LIVE_RANGE_1M;
        } catch (NumberFormatException ignored) {
            return LIVE_RANGE_1M;
        }
    }
}
