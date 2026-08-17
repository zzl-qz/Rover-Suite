package com.rover.common.constants;

/** Gateway 对外默认地址。 */
public final class GatewayConstants {

    private GatewayConstants() {
    }

    public static final int DEFAULT_PORT = 80;
    public static final String DEFAULT_HOST = NetworkConstants.IPV4_LOOPBACK;
    public static final String DEFAULT_BIND_HOST = NetworkConstants.IPV4_ANY;
    public static final String DEFAULT_URL =
            HttpConstants.SCHEME_HTTP + "://" + DEFAULT_HOST + ":" + DEFAULT_PORT;
}
