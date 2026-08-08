/**
 * 作者：Daylight
 * 创建时间：2026-08-08 11:37:00
 * 描述：封装管理端配置更新后的结果
 */
package com.rover.admin.service;

import com.rover.common.config.ConfigChangeEvent;
import com.rover.common.config.ConfigItem;
import lombok.Data;

@Data
public class ConfigUpdateResult {

    private final ConfigItem item;
    private final ConfigChangeEvent event;
    private final String message;
}
