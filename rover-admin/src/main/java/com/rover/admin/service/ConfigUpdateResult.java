package com.rover.admin.service;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 11:37:00
 * Description: 配置更新结果
 */
@Data
@AllArgsConstructor
public class ConfigUpdateResult {

    private String component;
    private Map<String, Object> payload;
    private String message;
}
