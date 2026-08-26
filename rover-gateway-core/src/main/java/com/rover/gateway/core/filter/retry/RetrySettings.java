package com.rover.gateway.core.filter.retry;

/**
 * 进程内换台重试。默认关。只救「还没把请求发出去」的连接失败。
 */
public class RetrySettings {

    private volatile boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
