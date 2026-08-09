package com.rover.common.protocol;

import lombok.Getter;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:40:00
 * Description: 写确认方式，后面集群可能会用到
 */
@Getter
public enum AckMode {

    // 本机接了就回(异步)
    IMMEDIATE((byte) 0),

    // 多数副本确认后再回(半同步)
    MAJORITY((byte) 1),

    // 全部副本确认后再回(同步)
    ALL((byte) 2);

    private final byte code;

    AckMode(byte code) {
        this.code = code;
    }

    /**
     * 根据 code 获取 ACK mode
     */
    public static AckMode fromCode(byte code) {
        for (AckMode mode : values()) {
            if (mode.code == code) {
                return mode;
            }
        }
        throw new IllegalArgumentException("未知 AckMode: " + code);
    }

    /**
     * 根据名称获取 ACK mode
     */
    public static AckMode fromName(String name) {
        if (name == null || name.isBlank()) {
            return IMMEDIATE;
        }
        return AckMode.valueOf(name.trim().toUpperCase());
    }
}
