package com.rover.common.config;

import com.rover.common.event.Event;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Author: Daylight
 * Created: 2026-08-08 11:37:00
 * Description: 配置更新事件
 */
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class ConfigChangeEvent extends Event {

    /** 变更的配置项 key */
    private String key;
    /** 变更前的值，首次配置可能为 null */
    private String oldValue;
    /** 变更后的新值 */
    private String newValue;
    /** 生效方式：热更新或需重启 */
    private ConfigApplyMode applyMode;
    /** 变更发生时间(epoch 毫秒) */
    private long changeTime;
}
