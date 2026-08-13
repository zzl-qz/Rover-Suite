package com.rover.common.event;

/**
 * Author: Daylight
 * Created: 2026-08-13 00:00:00
 * Description: 服务实例变更类型
 */
public enum ServiceChangeType {

    /** 实例注册或续注册导致快照变化 */
    REGISTER,

    /** 主动注销 */
    UNREGISTER,

    /** 健康检查剔除（过期） */
    EXPIRE,

    /** 订阅后的初始全量快照 */
    SNAPSHOT,

    /** 未分类 */
    UNKNOWN
}
