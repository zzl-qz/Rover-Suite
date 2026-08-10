package com.rover.common.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Author: Daylight
 * Created: 2026-08-08 11:37:00
 * Description: 描述可在管理端展示和修改的配置项
 *
 * 这个类是什么：配置项的元信息描述模型。
 * 核心职责：把某个可管理配置的 key、当前值、默认值、说明、生效方式、是否敏感
 * 聚合在一起，供管理端渲染配置页面以及 RuntimeConfigManager 校验入参。
 * 被谁用：RuntimeConfigManager.listConfigs 的返回元素；管理端(rover-admin)读取展示。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfigItem {

    /** 配置项唯一 key */
    private String key;
    /** 当前生效值 */
    private String value;
    /** 出厂默认值 */
    private String defaultValue;
    /** 面向管理员的说明文案 */
    private String description;
    /** 生效方式：热更新或需重启 */
    private ConfigApplyMode applyMode;
    /** 是否敏感项(如密码/密钥)，管理端展示时需脱敏 */
    private boolean sensitive;

    /**
     * 判断该配置项是否支持热更新。
     *
     * @return true 表示 applyMode 为 HOT_RELOAD，修改后可立即生效
     */
    public boolean isHotReloadable() {
        return ConfigApplyMode.HOT_RELOAD.equals(applyMode);
    }
}
