package com.rover.common.config;

import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:59:00
 * Description: 定义组件运行时配置查询和修改契约
 *
 * 这个接口是什么：组件对外暴露运行时配置能力的 SPI 契约。
 * 核心职责：让每个组件(网关/注册中心等)统一提供「有哪些配置、是否管理某个 key、
 * 如何更新某一个配置」三个能力，管理端借助它做统一的配置管理与热更新。
 * 被谁用：实现方为各具名组件；调用方为管理端(rover-admin)与配置下发链路。
 */
public interface RuntimeConfigManager {

    /**
     * 返回当前组件暴露给管理端的配置项。
     *
     * @return 配置项列表，通常来自组件的默认配置描述
     */
    List<ConfigItem> listConfigs();

    /**
     * 判断当前组件是否管理指定配置项。
     *
     * @param key 配置项 key
     * @return true 表示该 key 由本组件接管
     */
    boolean supports(String key);

    /**
     * 更新指定配置项，并返回配置变更事件。
     *
     * @param key   配置项 key
     * @param value 新的配置值
     * @return 封装了新旧值与生效方式的变更事件，可进一步投递到 EventBus
     */
    ConfigChangeEvent updateConfig(String key, String value);
}
