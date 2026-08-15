package com.rover.common.protocol;

/**
 * Author: Daylight
 * Created: 2026-08-07 09:25:00
 * Description: 帧头 16 位 flags 的位级工具：低 2 位存 ack 模式，其余位为独立开关位
 */
public final class ProtocolFlags {

    /** 工具类不允许实例化 */
    private ProtocolFlags() {
    }

    /** ack 模式的位掩码：仅低 2 位有效 */
    public static final int ACK_MODE_MASK = 0b11;

    /** oneway 标志位：置位表示无需回应 */
    public static final int FLAG_ONEWAY = 1 << 2;

    /** 压缩标志位(预留) */
    public static final int FLAG_COMPRESS = 1 << 3;
    /** 重定向提示标志位(预留) */
    public static final int FLAG_REDIRECT_HINT = 1 << 4;
    /** 需持久化标志位(预留) */
    public static final int FLAG_REQUIRE_PERSIST = 1 << 5;

    /** 当前协议支持的全部位，校验时用于过滤未知位 */
    public static final int SUPPORTED_MASK =
            ACK_MODE_MASK | FLAG_ONEWAY | FLAG_COMPRESS | FLAG_REDIRECT_HINT | FLAG_REQUIRE_PERSIST;

    /** 空 flags：ack 模式为 SINGLE，其余位全 0。 */
    public static short empty() {
        return withAckMode((short) 0, AckMode.SINGLE);
    }

    /** 重写 flags 中的 ack 模式（先清掉低 2 位再写入新值）。 */
    public static short withAckMode(short flags, AckMode ackMode) {
        AckMode mode = ackMode == null ? AckMode.SINGLE : ackMode;
        int cleared = flags & ~ACK_MODE_MASK;
        return (short) (cleared | (mode.getCode() & ACK_MODE_MASK));
    }

    /** 取出 flags 中的 ack 模式；低 2 位编码未知时抛 IllegalArgumentException。 */
    public static AckMode ackModeOf(short flags) {
        return AckMode.fromCode((byte) (flags & ACK_MODE_MASK));
    }

    /** 置位：打开指定标志位。 */
    public static short enable(short flags, int flagBit) {
        return (short) (flags | flagBit);
    }

    /** 清位：关闭指定标志位。 */
    public static short disable(short flags, int flagBit) {
        return (short) (flags & ~flagBit);
    }

    /** 判断指定标志位是否置位。 */
    public static boolean has(short flags, int flagBit) {
        return (flags & flagBit) != 0;
    }

    // 未知扩展位直接拒绝，避免两端语义不一致
    /** 校验 flags 是否全部位于协议支持位范围内；含未知扩展位抛 IllegalArgumentException。 */
    public static void validateSupported(short flags) {
        int unknown = flags & ~SUPPORTED_MASK;
        if (unknown != 0) {
            throw new IllegalArgumentException(
                    "协议 flags 包含尚未支持的扩展位: 0x" + Integer.toHexString(unknown));
        }
    }
}
