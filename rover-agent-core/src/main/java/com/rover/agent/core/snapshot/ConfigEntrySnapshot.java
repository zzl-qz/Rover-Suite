package com.rover.agent.core.snapshot;

/**
 * 只读配置项的时点快照，统计窗口为 0，样本量为配置条目数。
 *
 * @param component     所属组件（gateway / nameserver）
 * @param key           配置键
 * @param description   配置说明
 * @param value         当前生效值
 * @param defaultValue  默认值
 * @param applyMode     生效方式（HOT_RELOAD / RESTART 等）
 * @param hotReloadable 是否支持热更新
 * @param unavailable   该条是否为「读取失败」占位项
 */
public record ConfigEntrySnapshot(String component, String key, String description, String value,
                                  String defaultValue, String applyMode, boolean hotReloadable,
                                  boolean unavailable) { }
