/**
 * 作者：Daylight
 * 创建时间：2026-08-08 14:59:00
 * 描述：定义组件运行时配置查询和修改契约
 */
package com.rover.common.config;

import java.util.List;

public interface RuntimeConfigManager {

    /**
     * 返回当前组件暴露给管理端的配置项。
     */
    List<ConfigItem> listConfigs();

    /**
     * 判断当前组件是否管理指定配置项。
     */
    boolean supports(String key);

    /**
     * 更新指定配置项，并返回配置变更事件。
     */
    ConfigChangeEvent updateConfig(String key, String value);
}
