/**
 * 作者：Daylight
 * 创建时间：2026-08-08 11:37:00
 * 描述：描述可在管理端展示和修改的配置项
 */
package com.rover.common.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfigItem {

    private String key;
    private String value;
    private String defaultValue;
    private String description;
    private ConfigApplyMode applyMode;
    private boolean sensitive;

    public boolean isHotReloadable() {
        return ConfigApplyMode.HOT_RELOAD.equals(applyMode);
    }
}
