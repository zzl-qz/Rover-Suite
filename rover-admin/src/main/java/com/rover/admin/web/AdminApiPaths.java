package com.rover.admin.web;

/** Admin SPA 对外 API 路径。 */
public final class AdminApiPaths {

    private AdminApiPaths() {
    }

    public static final String PREFIX = "/api";
    public static final String OVERVIEW = "/overview";
    public static final String LIVE = "/live";
    public static final String METRICS = "/metrics";
    public static final String METRICS_ROUTES = "/metrics/routes";
    public static final String ROUTES = "/routes";
    /** 路由变更预览：只校验与比对，不落盘、不生效。 */
    public static final String ROUTES_PREVIEW = "/routes/preview";
    /** 单个版本目标的权重调整接口，使用 revision 乐观锁。 */
    public static final String ROUTES_TARGET_WEIGHT = "/routes/targets/weight";
    /** 回滚到最近某次已应用的路由快照（产生新版本，不是覆盖历史）。 */
    public static final String ROUTES_ROLLBACK = "/routes/rollback";
    /** 路由写操作记录查询：请求超时后用同一个 operationId 确认是否已执行。 */
    public static final String ROUTE_OPERATION = "/routes/operations/{operationId}";
    public static final String INSTANCES = "/instances";
    public static final String NAMESERVER_METRICS = "/nameserver/metrics";
    public static final String EVENTS = "/events";
    public static final String TRACES = "/traces";
    public static final String CONFIGS = "/configs";
    /** 落盘记录库历史区间查询（配置变更/回滚/部署/实例事件等证据）。 */
    public static final String LOGS = "/logs";

    /** 当前登录态与 CSRF 令牌下发。 */
    public static final String AUTH_STATUS = "/auth/status";
    /** 登出。 */
    public static final String LOGOUT = "/logout";

    /** 模型配置读写。 */
    public static final String MODEL_CONFIG = "/model/config";
    /** 用提交的候选值做连接测试，不落盘。 */
    public static final String MODEL_TEST = "/model/test";
    /** 对当前已生效的配置做效果验证。 */
    public static final String MODEL_VERIFY = "/model/verify";
}
