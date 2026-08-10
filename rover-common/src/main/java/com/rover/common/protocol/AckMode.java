package com.rover.common.protocol;

import lombok.Getter;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:40:00
 * Description: 写确认方式，后面集群可能会用到
 *
 * 这个类是什么：消息写入确认等级枚举。
 * 核心职责：用 1 字节 code 表达「本地即回 / 多数副本确认 / 全部确认」三档可靠性，
 * 供 flags 位与应答体记录实际生效的 ack 等级。
 * 被谁用：ProtocolFlags 位操作、CommonResponseBody 应答、客户端指定写可靠性。
 */
@Getter
public enum AckMode {

    // 本机接了就回(异步)
    /** 立即返回：本机接收即回，异步语义 */
    IMMEDIATE((byte) 0),

    // 多数副本确认后再回(半同步)
    /** 多数派确认：超过半数副本落盘后返回，半同步语义 */
    MAJORITY((byte) 1),

    // 全部副本确认后再回(同步)
    /** 全部确认：所有副本落盘后返回，同步语义 */
    ALL((byte) 2);

    /** 协议层使用的字节编码 */
    private final byte code;

    /**
     * 构造枚举项。
     *
     * @param code 字节编码
     */
    AckMode(byte code) {
        this.code = code;
    }

    /**
     * 根据 code 获取 ACK mode
     *
     * @param code 字节编码
     * @return 匹配的枚举项
     * @throws IllegalArgumentException 传入未定义的 code
     */
    public static AckMode fromCode(byte code) {
        // 线性扫描全部枚举项匹配编码
        for (AckMode mode : values()) {
            if (mode.code == code) {
                return mode;
            }
        }
        throw new IllegalArgumentException("未知 AckMode: " + code);
    }

    /**
     * 根据名称获取 ACK mode
     *
     * @param name 枚举名称(忽略大小写与首尾空白)；null/空白返回 IMMEDIATE
     * @return 匹配的枚举项
     * @throws IllegalArgumentException 传入的名称不合法
     */
    public static AckMode fromName(String name) {
        // 空名兜底为最宽松的 IMMEDIATE
        if (name == null || name.isBlank()) {
            return IMMEDIATE;
        }
        return AckMode.valueOf(name.trim().toUpperCase());
    }
}
