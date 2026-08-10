package com.rover.common.protocol;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:40:00
 * Description: 帧头 flags 位操作
 *
 * 这个类是什么：Rover 协议帧头 16 位 flags 的位级工具类。
 * 核心职责：用位掩码封装 flags 的「读写/开关/校验」操作——低 2 位存 ack 模式，
 * 其余位为独立开关位；让编解码双方对标志位的理解保持唯一。
 * 被谁用：协议编解码器(Encoder/Decoder)与 RoverMessage 的读改写。
 */
public final class ProtocolFlags {

    /** 工具类不允许实例化 */
    private ProtocolFlags() {
    }

    // 低 2 位放 ack 模式
    /** ack 模式的位掩码：仅低 2 位有效 */
    public static final int ACK_MODE_MASK = 0b11;

    /** oneway 标志位：置位表示无需回应 */
    public static final int FLAG_ONEWAY = 1 << 2;

    // 下面几个先留着，现在不用
    /** 压缩标志位(预留) */
    public static final int FLAG_COMPRESS = 1 << 3;
    /** 重定向提示标志位(预留) */
    public static final int FLAG_REDIRECT_HINT = 1 << 4;
    /** 需持久化标志位(预留) */
    public static final int FLAG_REQUIRE_PERSIST = 1 << 5;

    /** 当前协议支持的全部位，校验时用于过滤未知位 */
    public static final int SUPPORTED_MASK =
            ACK_MODE_MASK | FLAG_ONEWAY | FLAG_COMPRESS | FLAG_REDIRECT_HINT | FLAG_REQUIRE_PERSIST;

    /**
     * 空 flags：ack 模式为 IMMEDIATE，其余位全 0。
     *
     * @return 默认 flags 值
     */
    public static short empty() {
        return withAckMode((short) 0, AckMode.IMMEDIATE);
    }

    /**
     * 重写 flags 中的 ack 模式(先清掉低 2 位再写入新值)。
     *
     * @param flags   原 flags
     * @param ackMode 新 ack 模式；null 回落为 IMMEDIATE
     * @return 改写过 ack 位的新 flags
     */
    public static short withAckMode(short flags, AckMode ackMode) {
        AckMode mode = ackMode == null ? AckMode.IMMEDIATE : ackMode;
        int cleared = flags & ~ACK_MODE_MASK; // 将低位清零保留其余标志位
        return (short) (cleared | (mode.getCode() & ACK_MODE_MASK)); // 写入新 ack 编码
    }

    /**
     * 取出 flags 中的 ack 模式。
     *
     * @param flags 原 flags
     * @return 解析出的 AckMode
     * @throws IllegalArgumentException 低 2 位编码未知时由 AckMode.fromCode 抛出
     */
    public static AckMode ackModeOf(short flags) {
        return AckMode.fromCode((byte) (flags & ACK_MODE_MASK));
    }

    /**
     * 置位：打开指定标志位。
     *
     * @param flags   原 flags
     * @param flagBit 待置位的位(如 FLAG_ONEWAY)
     * @return 置位后的 flags
     */
    public static short enable(short flags, int flagBit) {
        return (short) (flags | flagBit);
    }

    /**
     * 清位：关闭指定标志位。
     *
     * @param flags   原 flags
     * @param flagBit 待清除的位(如 FLAG_ONEWAY)
     * @return 清除后的 flags
     */
    public static short disable(short flags, int flagBit) {
        return (short) (flags & ~flagBit);
    }

    /**
     * 判断某个标志位是否置位。
     *
     * @param flags   原 flags
     * @param flagBit 待检测的位
     * @return true 表示该位为 1
     */
    public static boolean has(short flags, int flagBit) {
        return (flags & flagBit) != 0;
    }

    // 不认识的位直接拒掉，免得两边理解不一致
    /**
     * 校验 flags 是否全部位于协议支持位范围内。
     *
     * @param flags 待校验 flags
     * @throws IllegalArgumentException 含有未支持的扩展位
     */
    public static void validateSupported(short flags) {
        int unknown = flags & ~SUPPORTED_MASK; // 取出未知位
        if (unknown != 0) {
            throw new IllegalArgumentException(
                    "协议 flags 包含尚未支持的扩展位: 0x" + Integer.toHexString(unknown));
        }
    }
}
