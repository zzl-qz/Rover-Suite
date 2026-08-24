package com.rover.gateway.core.filter.ratelimit;

import java.util.Locale;

/**
 * Gateway 内置本地限流配置。默认关闭，避免改变现有部署行为。
 */
public class RateLimitSettings {

    public static final String TOKEN_BUCKET = "token_bucket";
    public static final String SLIDING_WINDOW = "sliding_window";
    public static final String GLOBAL = "global";
    public static final String PATH = "path";

    private volatile boolean enabled;
    private volatile String algorithm = TOKEN_BUCKET;
    private volatile String key = PATH;
    private volatile long permitsPerSecond = 1000;
    private volatile long burst = 2000;
    private volatile long limit = 1000;
    private volatile int windowSeconds = 1;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm == null || algorithm.isBlank()
                ? TOKEN_BUCKET : algorithm.trim().toLowerCase(Locale.ROOT);
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key == null || key.isBlank()
                ? PATH : key.trim().toLowerCase(Locale.ROOT);
    }

    public long getPermitsPerSecond() {
        return permitsPerSecond;
    }

    public void setPermitsPerSecond(long permitsPerSecond) {
        this.permitsPerSecond = permitsPerSecond;
    }

    public long getBurst() {
        return burst;
    }

    public void setBurst(long burst) {
        this.burst = burst;
    }

    public long getLimit() {
        return limit;
    }

    public void setLimit(long limit) {
        this.limit = limit;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }
}
