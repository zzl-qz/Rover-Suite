package com.rover.common.config;

/**
 * Author: Daylight
 * Created: 2026-08-08 11:37:00
 * Description: 定义配置变更的生效方式
 *
 * 这个类是什么：配置生效方式枚举。
 * 核心职责：标明某配置项修改后是立即热生效，还是必须重启进程才能生效，
 * 供 RuntimeConfigManager/管理端决定变更后的处理策略。
 * 被谁用：ConfigItem、ConfigChangeEvent 以及实现 RuntimeConfigManager 的各组件。
 */
public enum ConfigApplyMode {

    /** 热更新：运行时直接应用，无需重启 */
    HOT_RELOAD,
    /** 需要重启：只落盘，重启后才真正生效 */
    RESTART_REQUIRED
}
