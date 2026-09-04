package com.rover.gateway.core.config;

import com.rover.common.constants.NetworkConstants;
import com.rover.common.constants.GatewayConstants;
import com.rover.common.constants.ProtocolConstants;

/** Gateway 启动与运行时默认值的单一来源。 */
public final class GatewayDefaults {

    private GatewayDefaults() {
    }

    public static final int HTTP_PORT = GatewayConstants.DEFAULT_PORT;
    public static final String BIND_HOST = GatewayConstants.DEFAULT_BIND_HOST;
    public static final int MAX_REQUEST_BODY_BYTES = ProtocolConstants.MAX_BODY_LENGTH;
    public static final int MAX_RESPONSE_BODY_BYTES = 16 * 1024 * 1024;
    public static final int CONNECT_TIMEOUT_MILLIS = 3_000;
    public static final int REQUEST_TIMEOUT_MILLIS = 30_000;
    /** 每个 EventLoop、每个后端 host:port 各一个池，池内最多这么多条连接。 */
    public static final int MAX_CONNECTIONS_PER_EVENT_LOOP = 64;
    /** 单个池满了以后，最多排队等连接的请求数。 */
    public static final int MAX_PENDING_ACQUIRES = 256;
    public static final long RECONCILE_INTERVAL_MILLIS = 30_000L;
    public static final int METRICS_WINDOW_SECONDS = 300;
    public static final long TRACE_SLOW_THRESHOLD_MILLIS = 100L;
    public static final double TRACE_SAMPLE_RATE = 0D;
    public static final long CORS_MAX_AGE_SECONDS = 1_800L;

    /** 在途闸门默认：CPU×8，下限 64。 */
    public static int defaultMaxInflight() {
        return Math.max(64, Runtime.getRuntime().availableProcessors() * 8);
    }

    /** 没填或填 0/负数时用默认。YAML 里 0 表示「跟出厂走」。 */
    public static int positiveOrDefault(int raw, int defaultValue) {
        return raw <= 0 ? defaultValue : raw;
    }

    /** 读 -D 覆盖；没设或设了 0 就用 defaultValue。 */
    public static int intPropertyOrDefault(String key, int defaultValue) {
        return positiveOrDefault(Integer.getInteger(key, 0), defaultValue);
    }
}
