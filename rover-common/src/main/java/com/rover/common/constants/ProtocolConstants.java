package com.rover.common.constants;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 协议常量和消息类型
 */
public final class ProtocolConstants {

    private ProtocolConstants() {
    }

    public static final int MAGIC_NUMBER = 0xCAFE;

    public static final byte VERSION = 1;

    /**
     * magic(2) + version(1) + type(1) + flags(2)
     * + requestId(8) + timeoutMs(4) + bodyLength(4)
     */
    public static final int HEADER_LENGTH = 22;

    // 防一手异常大包
    public static final int MAX_BODY_LENGTH = 1024 * 1024;

    // 0 表示跟服务端默认走
    public static final int DEFAULT_TIMEOUT_MS = 3000;

    // ---- 业务消息 1~31 ----

    public static final byte REGISTER_REQUEST = 1;
    public static final byte UNREGISTER_REQUEST = 2;
    public static final byte HEARTBEAT_REQUEST = 3;
    public static final byte QUERY_REQUEST = 4;
    public static final byte PUSH_RESPONSE = 5;
    public static final byte SUBSCRIBE_REQUEST = 6;
    public static final byte COMMON_RESPONSE = 7;
    public static final byte UNSUBSCRIBE_REQUEST = 8;

    // ---- 集群内部先占坑，暂时不用 ----

    public static final byte CLUSTER_REPLICATE_REQUEST = 32;
    public static final byte CLUSTER_REPLICATE_RESPONSE = 33;
    public static final byte CLUSTER_NODE_HEARTBEAT = 34;
    public static final byte CLUSTER_REDIRECT = 35;
}
