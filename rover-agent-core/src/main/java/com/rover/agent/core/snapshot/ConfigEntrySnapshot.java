package com.rover.agent.core.snapshot;

/**
 * 一条只读配置项快照。
 *
 * 配置是「时点快照」而非窗口统计：它只说明当前生效值，不能证明某次请求为什么失败，
 * 因此{@code windowSeconds} 口径为 0，样本量记为读取到的配置条目数。
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
