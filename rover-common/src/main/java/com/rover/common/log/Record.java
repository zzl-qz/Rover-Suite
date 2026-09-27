package com.rover.common.log;

/**
 * 一条追加式记录：带时间戳、类型、归属实体（路由/服务/实例）与负载（多为 JSON 文本）。
 * 不可变，写入后不应修改。
 */
public record Record(long ts, RecordType type, String target, String payload) {

    /** 以当前时间构造一条记录。 */
    public static Record of(RecordType type, String target, String payload) {
        return new Record(System.currentTimeMillis(), type, target, payload);
    }
}
