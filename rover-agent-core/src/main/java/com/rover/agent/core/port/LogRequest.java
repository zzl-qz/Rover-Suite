package com.rover.agent.core.port;

import java.util.List;

/**
 * Agent 查询历史日志的请求：时间范围 + 可选归属实体 + 可选类型 + 条数上限。
 * 类型用字符串以解耦底层枚举，适配器负责把它映射成存储层类型。
 */
public record LogRequest(String target, Long from, Long to, List<String> types, int limit) {
}
