package com.rover.common.config;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Author: Daylight
 * Created: 2026-08-08 11:37:00
 * Description: 可管理配置项的元信息模型：key/当前值/默认值/说明/生效方式/是否敏感，供管理端渲染与运行时校验
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
    /** 预设可选值（管理端渲染为可选择项）；为空表示自由输入 */
    private List<String> options;

    /** 是否支持热更新（applyMode 为 HOT_RELOAD）。 */
    public boolean isHotReloadable() {
        return ConfigApplyMode.HOT_RELOAD.equals(applyMode);
    }
}
