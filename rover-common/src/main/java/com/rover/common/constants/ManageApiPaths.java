package com.rover.common.constants;

/** Gateway、Nameserver 与 Admin 共同使用的管理 API 路径。 */
public final class ManageApiPaths {

    private ManageApiPaths() {
    }

    public static final String PREFIX = "/_manage";
    public static final String STATUS = PREFIX + "/status";
    public static final String CONFIGS = PREFIX + "/configs";
    public static final String ROUTES = PREFIX + "/routes";
    public static final String METRICS = PREFIX + "/metrics";
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
}
