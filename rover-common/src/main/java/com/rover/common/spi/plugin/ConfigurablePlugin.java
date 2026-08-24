package com.rover.common.spi.plugin;

import java.util.List;

/**
 * 可选插件配置契约。
 * 实现类的字段会出现在 Admin 的“插件配置”分组中，并复用 Gateway 的热更新与 overlay 落盘能力。
 */
public interface ConfigurablePlugin {

    /** 唯一命名空间，例如 request-tag；同一 Gateway 内不能与其他可配置插件重复。 */
    String configNamespace();

    /** 返回可展示、可编辑的配置元数据；通常使用 {@link List#of(Object[])} 声明固定列表。 */
    List<PluginConfigProperty> configProperties();

    /** 在保存前校验一个字段；非法值抛 {@link IllegalArgumentException}。 */
    default void validateConfig(String key, String value) {
    }

    /** 应用已通过校验的新值；应避免阻塞、远程 I/O 与不可逆副作用。 */
    void applyConfig(String key, String value);
}
