package com.rover.common.constants;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 协议常量和消息类型
 *
 * 这个类是什么：Rover TCP 二进制协议的全部常量集中地。
 * 核心职责：统一定义魔数、版本、帧头长度、消息类型等编解码双方都必须一致的数值，
 * 防止两端(客户端/服务端)对协议的理解出现偏差。
 * 被谁用：协议编解码器(Decoder/Encoder)、RoverMessage 及各请求/响应体。
 */
public final class ProtocolConstants {

    /** 工具类不允许实例化 */
    private ProtocolConstants() {
    }

    /** 魔数 0xCAFE，用于识别 Rover 协议帧 */
    public static final int MAGIC_NUMBER = 0xCAFE;

    /** 协议版本号 */
    public static final byte VERSION = 1;

    /**
     * 帧头固定长度 22 字节，布局如下：
     * magic(2) + version(1) + type(1) + flags(2)
     * + requestId(8) + timeoutMs(4) + bodyLength(4)
     */
    public static final int HEADER_LENGTH = 22;

    /** 防一手异常大包，body 最大长度 1MB，超过直接拒绝，防止恶意大包打爆内存 */
    public static final int MAX_BODY_LENGTH = 1024 * 1024;

    // 0 表示跟服务端默认走
    /** 默认超时 3 秒；报文中为 0 时服务端按此值处理 */
    public static final int DEFAULT_TIMEOUT_MS = 3000;

    // ---- 业务消息 1~31 ----

    /** 注册请求 */
    public static final byte REGISTER_REQUEST = 1;
    /** 注销请求 */
    public static final byte UNREGISTER_REQUEST = 2;
    /** 心跳请求 */
    public static final byte HEARTBEAT_REQUEST = 3;
    /** 查询实例请求 */
    public static final byte QUERY_REQUEST = 4;
    /** 服务变更推送响应 */
    public static final byte PUSH_RESPONSE = 5;
    /** 订阅服务变更请求 */
    public static final byte SUBSCRIBE_REQUEST = 6;
    /** 通用响应 */
    public static final byte COMMON_RESPONSE = 7;
    /** 取消订阅请求 */
    public static final byte UNSUBSCRIBE_REQUEST = 8;

    // ---- 集群内部先占坑，暂时不用 ----

    /** 集群复制请求(预留) */
    public static final byte CLUSTER_REPLICATE_REQUEST = 32;
    /** 集群复制响应(预留) */
    public static final byte CLUSTER_REPLICATE_RESPONSE = 33;
    /** 集群节点心跳(预留) */
    public static final byte CLUSTER_NODE_HEARTBEAT = 34;
    /** 集群重定向(预留) */
    public static final byte CLUSTER_REDIRECT = 35;
}
