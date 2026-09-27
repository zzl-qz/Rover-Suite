package com.rover.common.log;

import java.util.List;

/**
 * 区间查询条件：时间范围 + 可选归属实体 + 可选类型过滤 + 条数上限。
 * from/to 为毫秒时间戳；任一为 null 表示不限制该侧边界。
 */
public record LogQuery(Long from, Long to, String target, List<RecordType> types, int limit) {

    public static LogQuery of(Long from, Long to, String target, List<RecordType> types, int limit) {
        return new LogQuery(from, to, target, types, limit);
    }
}
