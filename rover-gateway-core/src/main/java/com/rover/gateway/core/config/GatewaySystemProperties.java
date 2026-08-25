package com.rover.gateway.core.config;

/** Gateway 支持的 JVM 系统属性名。 */
public final class GatewaySystemProperties {

    private GatewaySystemProperties() {
    }

    public static final String BIZ_THREADS = "rover.gateway.bizThreads";
    public static final String MAX_INFLIGHT = "rover.gateway.maxInflight";
    public static final String DISPATCH_ON_EVENT_LOOP = "rover.gateway.dispatchOnEventLoop";
    public static final String PROXY_OUTBOUND = "rover.gateway.proxy.outbound";
}
