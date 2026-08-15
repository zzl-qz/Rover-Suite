package com.rover.common.protocol;

import lombok.Getter;

/**
 * Author: Daylight
 * Created: 2026-08-07 09:30:00
 * Description: 消息写确认等级：本地即回/多数派确认/全部确认，供 flags 与应答体记录实际 ack
 */
@Getter
public enum AckMode {

    /** 单机确认：本机接收即回，异步语义 */
    SINGLE((byte) 0),

    /** 多数派确认：超过半数副本落盘后返回，半同步语义 */
    HALF((byte) 1),

    /** 全部确认：所有副本落盘后返回，同步语义 */
    ALL((byte) 2);

    /** 协议层使用的字节编码 */
    private final byte code;

    AckMode(byte code) {
        this.code = code;
    }

    /** 按 code 查枚举；未定义抛 IllegalArgumentException。 */
    public static AckMode fromCode(byte code) {
        for (AckMode mode : values()) {
            if (mode.code == code) {
                return mode;
            }
        }
        throw new IllegalArgumentException("未知 AckMode: " + code);
    }

    /** 按名称查枚举（忽略大小写与首尾空白）；null/空白回落 SINGLE。 */
    public static AckMode fromName(String name) {
        // 空名兜底为最宽松的 SINGLE
        if (name == null || name.isBlank()) {
            return SINGLE;
        }
        return AckMode.valueOf(name.trim().toUpperCase());
    }
}
