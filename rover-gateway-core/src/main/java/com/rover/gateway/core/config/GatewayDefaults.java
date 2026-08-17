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
    public static final long RECONCILE_INTERVAL_MILLIS = 30_000L;
    public static final int METRICS_WINDOW_SECONDS = 300;
    public static final long TRACE_SLOW_THRESHOLD_MILLIS = 100L;
    public static final double TRACE_SAMPLE_RATE = 0D;
    public static final long CORS_MAX_AGE_SECONDS = 1_800L;
}
