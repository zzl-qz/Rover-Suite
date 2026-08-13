package com.rover.common.constants;

/**
 * Author: Daylight
 * Created: 2026-08-13 00:00:00
 * Description: 协议 type → 可读名称（线上仍用数字 code，日志/排障用名字）
 *
 * 哲学：协议码是稳定契约；名字是给人看的领域语言。
 */
public final class ProtocolTypeNames {

    private ProtocolTypeNames() {
    }

    /** 未知类型返回 type=数字，避免 NPE */
    public static String nameOf(byte type) {
        return switch (type) {
            case ProtocolConstants.REGISTER_REQUEST -> "REGISTER_REQUEST";
            case ProtocolConstants.UNREGISTER_REQUEST -> "UNREGISTER_REQUEST";
            case ProtocolConstants.HEARTBEAT_REQUEST -> "HEARTBEAT_REQUEST";
            case ProtocolConstants.QUERY_REQUEST -> "QUERY_REQUEST";
            case ProtocolConstants.PUSH_RESPONSE -> "PUSH_RESPONSE";
            case ProtocolConstants.SUBSCRIBE_REQUEST -> "SUBSCRIBE_REQUEST";
            case ProtocolConstants.COMMON_RESPONSE -> "COMMON_RESPONSE";
            case ProtocolConstants.UNSUBSCRIBE_REQUEST -> "UNSUBSCRIBE_REQUEST";
            case ProtocolConstants.CLUSTER_REPLICATE_REQUEST -> "CLUSTER_REPLICATE_REQUEST";
            case ProtocolConstants.CLUSTER_REPLICATE_RESPONSE -> "CLUSTER_REPLICATE_RESPONSE";
            case ProtocolConstants.CLUSTER_NODE_HEARTBEAT -> "CLUSTER_NODE_HEARTBEAT";
            case ProtocolConstants.CLUSTER_REDIRECT -> "CLUSTER_REDIRECT";
            default -> "UNKNOWN(type=" + type + ")";
        };
    }
}
