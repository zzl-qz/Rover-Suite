package com.rover.common.config;

/**
 * Author: Daylight
 * Created: 2026-08-02 14:30:00
 * Description: 配置变更生效方式：热更新或需重启
 */
public enum ConfigApplyMode {

    /** 热更新：运行时直接应用，无需重启 */
    HOT_RELOAD,
    /** 需要重启：只落盘，重启后才真正生效 */
    RESTART_REQUIRED
}
