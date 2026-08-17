package com.rover.gateway.core.trace;

import com.rover.gateway.core.config.GatewayDefaults;

/**
 * Author: Daylight
 * Description: 请求时间线采集配置（支持热更新）：
 * 默认只记录慢请求（总耗时 >= slowThresholdMillis），可叠加按比例采样，可整体关闭。
 */
public class TraceSettings {

    /** 总开关，false 时完全不采集时间线。 */
    private volatile boolean enabled = true;

    /** 慢请求阈值（毫秒），总耗时超过即记录。 */
    private volatile long slowThresholdMillis = GatewayDefaults.TRACE_SLOW_THRESHOLD_MILLIS;

    /** 采样率 0~1，0 表示只记慢请求，1 表示全量。 */
    private volatile double sampleRate = GatewayDefaults.TRACE_SAMPLE_RATE;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getSlowThresholdMillis() {
        return slowThresholdMillis;
    }

    public void setSlowThresholdMillis(long slowThresholdMillis) {
        this.slowThresholdMillis = slowThresholdMillis;
    }

    public double getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(double sampleRate) {
        this.sampleRate = sampleRate;
    }
}
