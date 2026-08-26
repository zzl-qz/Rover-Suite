package com.rover.gateway.core.filter.circuit;

import java.util.Locale;

/**
 * 进程内熔断配置。默认关，避免改变现有部署行为。
 */
public class CircuitBreakerSettings {

    public static final String ALL = "all";
    public static final String HALF = "half";

    private volatile boolean enabled;
    private volatile int failureThreshold = 5;
    private volatile int openSeconds = 10;
    private volatile String recovery = ALL;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public int getOpenSeconds() {
        return openSeconds;
    }

    public void setOpenSeconds(int openSeconds) {
        this.openSeconds = openSeconds;
    }

    public String getRecovery() {
        return recovery;
    }

    public void setRecovery(String recovery) {
        String normalized = recovery == null || recovery.isBlank()
                ? ALL
                : recovery.trim().toLowerCase(Locale.ROOT);
        this.recovery = HALF.equals(normalized) ? HALF : ALL;
    }

    public boolean isAllRecovery() {
        return ALL.equals(recovery);
    }
}
