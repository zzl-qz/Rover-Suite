package com.rover.common.protocol;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:40:00
 * Description: 帧头 flags 位操作
 */
public final class ProtocolFlags {

    private ProtocolFlags() {
    }

    // 低 2 位放 ack 模式
    public static final int ACK_MODE_MASK = 0b11;

    public static final int FLAG_ONEWAY = 1 << 2;

    // 下面几个先留着，现在不用
    public static final int FLAG_COMPRESS = 1 << 3;
    public static final int FLAG_REDIRECT_HINT = 1 << 4;
    public static final int FLAG_REQUIRE_PERSIST = 1 << 5;

    public static final int SUPPORTED_MASK =
            ACK_MODE_MASK | FLAG_ONEWAY | FLAG_COMPRESS | FLAG_REDIRECT_HINT | FLAG_REQUIRE_PERSIST;

    public static short empty() {
        return withAckMode((short) 0, AckMode.IMMEDIATE);
    }

    public static short withAckMode(short flags, AckMode ackMode) {
        AckMode mode = ackMode == null ? AckMode.IMMEDIATE : ackMode;
        int cleared = flags & ~ACK_MODE_MASK;
        return (short) (cleared | (mode.getCode() & ACK_MODE_MASK));
    }

    public static AckMode ackModeOf(short flags) {
        return AckMode.fromCode((byte) (flags & ACK_MODE_MASK));
    }

    public static short enable(short flags, int flagBit) {
        return (short) (flags | flagBit);
    }

    public static short disable(short flags, int flagBit) {
        return (short) (flags & ~flagBit);
    }

    public static boolean has(short flags, int flagBit) {
        return (flags & flagBit) != 0;
    }

    // 不认识的位直接拒掉，免得两边理解不一致
    public static void validateSupported(short flags) {
        int unknown = flags & ~SUPPORTED_MASK;
        if (unknown != 0) {
            throw new IllegalArgumentException(
                    "协议 flags 包含尚未支持的扩展位: 0x" + Integer.toHexString(unknown));
        }
    }
}
