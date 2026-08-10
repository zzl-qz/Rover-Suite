package com.rover.common.config;

import com.rover.common.event.Event;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Author: Daylight
 * Created: 2026-08-08 11:37:00
 * Description: 承载配置变更后的运行时通知数据
 *
 * 这个类是什么：配置变更事件，实现 Event 接口，可投递到 EventBus。
 * 核心职责：把「哪个 key 从旧值变成了新值、生效方式、发生时间」打包成一帧事件，
 * 供订阅者(如网关、管理端)感知并响应配置变化。
 * 被谁用：RuntimeConfigManager.updateConfig 的返回值 + EventBus 的订阅端。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfigChangeEvent implements Event {

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
