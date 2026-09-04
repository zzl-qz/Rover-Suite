package com.rover.gateway.core.config;

import com.rover.common.constants.HttpConstants;

/** Gateway 支持的 JVM 系统属性名。 */
public final class GatewaySystemProperties {

    private GatewaySystemProperties() {
    }

    public static final String BIZ_THREADS = "rover.gateway.bizThreads";
    public static final String MAX_INFLIGHT = "rover.gateway.maxInflight";
    public static final String MAX_CONNECTIONS_PER_EVENT_LOOP =
            "rover.gateway.proxy.maxConnectionsPerEventLoop";
    public static final String MAX_PENDING_ACQUIRES = "rover.gateway.proxy.maxPendingAcquires";
    public static final String INBOUND_IDLE_TIMEOUT_SECONDS = "rover.gateway.server.idleTimeoutSeconds";
    public static final String REQUEST_IDLE_TIMEOUT_SECONDS = "rover.gateway.server.requestIdleTimeoutSeconds";
    public static final String OUTBOUND_IDLE_TIMEOUT_SECONDS = "rover.gateway.proxy.idleTimeoutSeconds";
    public static final String DISPATCH_ON_EVENT_LOOP = "rover.gateway.dispatchOnEventLoop";
    public static final String PROXY_OUTBOUND = "rover.gateway.proxy.outbound";
    /** auto / nio / epoll / kqueue。auto=有原生库就用，没有退 NIO。 */
    public static final String IO_TRANSPORT = "rover.gateway.ioTransport";

    /** netty 出站没有 TLS；https 上游只能走 jdk。 */
    public static boolean allowsHttpsUpstream(String outbound) {
        return outbound != null && "jdk".equalsIgnoreCase(outbound.trim());
    }

    public static boolean currentAllowsHttpsUpstream() {
        return allowsHttpsUpstream(System.getProperty(PROXY_OUTBOUND, "netty"));
    }

    /** scheme 不合法或当前出站扛不住时抛 IllegalArgumentException。 */
    public static void requireUpstreamScheme(String scheme, String targetUrl, String outbound) {
        if (HttpConstants.SCHEME_HTTP.equalsIgnoreCase(scheme)) {
            return;
        }
        if (HttpConstants.SCHEME_HTTPS.equalsIgnoreCase(scheme)) {
            if (allowsHttpsUpstream(outbound)) {
                return;
            }
            throw new IllegalArgumentException(
                    "netty 出站只支持 http 上游；https 请设 rover.gateway.proxy.outbound=jdk: " + targetUrl);
        }
        throw new IllegalArgumentException("targetUrl 只支持 http/https: " + targetUrl);
    }

    public static void requireUpstreamScheme(String scheme, String targetUrl) {
        requireUpstreamScheme(scheme, targetUrl, System.getProperty(PROXY_OUTBOUND, "netty"));
    }
}
