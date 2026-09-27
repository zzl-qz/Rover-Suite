package com.rover.admin.web;

/** Admin SPA 对外 API 路径。 */
public final class AdminApiPaths {

    private AdminApiPaths() {
    }

    public static final String PREFIX = "/api";
    public static final String OVERVIEW = "/overview";
    public static final String LIVE = "/live";
    public static final String ROUTES = "/routes";
    /** 路由变更预览：只校验与比对，不落盘、不生效。 */
    public static final String ROUTES_PREVIEW = "/routes/preview";
    /** 路由写操作记录查询：请求超时后用同一个 operationId 确认是否已执行。 */
    public static final String ROUTE_OPERATION = "/routes/operations/{operationId}";
    public static final String INSTANCES = "/instances";
    public static final String NAMESERVER_METRICS = "/nameserver/metrics";
    public static final String EVENTS = "/events";
    public static final String TRACES = "/traces";
    public static final String CONFIGS = "/configs";

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
