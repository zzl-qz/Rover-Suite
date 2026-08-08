package com.rover.common.config;

import com.rover.common.event.Event;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Author: Daylight
 * Created: 2026-08-08 11:37:00
 * Description: 承载配置变更后的运行时通知数据
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfigChangeEvent implements Event {

    private String key;
    private String oldValue;
    private String newValue;
    private ConfigApplyMode applyMode;
    private long changeTime;
}
