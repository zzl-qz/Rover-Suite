package com.rover.common.config;

import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-04 10:25:00
 * Description: 组件运行时配置查询/更新契约（SPI），管理端借此统一管理与热更新配置
 */
public interface RuntimeConfigManager {

    /** 当前组件暴露给管理端的配置项列表。 */
    List<ConfigItem> listConfigs();

    /** 当前组件是否管理指定配置项。 */
    boolean supports(String key);

    /** 更新配置项并返回封装了新旧值与生效方式的变更事件。 */
    ConfigChangeEvent updateConfig(String key, String value);
}
