package com.rover.gateway.core.metrics;

/**
 * Author: Daylight
 * Description: 指标采集运行时配置：总开关与滑动窗口时长，均支持热更新
 */
public class MetricsSettings {

    /** 指标采集总开关，异常时可一键降级关闭。 */
    private volatile boolean enabled = true;

    /** 滑动窗口时长（秒），按秒聚合，默认保留最近 5 分钟。 */
    private volatile int windowSeconds = 300;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }
}
