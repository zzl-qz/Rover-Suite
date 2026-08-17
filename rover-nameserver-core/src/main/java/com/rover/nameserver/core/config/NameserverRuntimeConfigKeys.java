package com.rover.nameserver.core.config;

import java.util.Set;

/** Nameserver 支持热更新的配置键。 */
public final class NameserverRuntimeConfigKeys {

    private NameserverRuntimeConfigKeys() {
    }

    public static final String HEALTH_CHECK_INTERVAL_MILLIS = "nameserver.health.checkIntervalMillis";
    public static final String HEARTBEAT_TIMEOUT_MILLIS = "nameserver.heartbeat.timeoutMillis";
    public static final String INSTANCE_EXPIRE_MILLIS = "nameserver.instance.expireMillis";
    public static final String PUSH_ENABLED = "nameserver.push.enabled";

    public static final Set<String> POSITIVE_DURATION_KEYS = Set.of(
            HEALTH_CHECK_INTERVAL_MILLIS,
            HEARTBEAT_TIMEOUT_MILLIS,
            INSTANCE_EXPIRE_MILLIS);
}
