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
    /**
     * 单版本权重调整：灰度放量 / 停推的专用原语。
     *
     * 与「打开整条路由编辑再整体保存」的区别是它只改一个版本的权重，内部仍走整表 + 乐观锁，
     * 因此既窄（不会误改别的目标）又安全（并发修改照样被 revision 拦下）。
     */
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
