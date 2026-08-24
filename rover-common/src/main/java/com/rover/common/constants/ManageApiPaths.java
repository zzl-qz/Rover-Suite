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
    public static final String METRICS = PREFIX + "/metrics";
    public static final String METRICS_LIVE = METRICS + "/live";
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
    public static final String PARAM_RANGE = "range";

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
