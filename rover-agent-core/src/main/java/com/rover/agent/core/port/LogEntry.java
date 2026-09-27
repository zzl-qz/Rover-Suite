package com.rover.agent.core.port;

/**
 * 一条历史日志/事件证据，供 Agent 做变更点分析。
 * 与具体存储解耦：底层可以是 H2、文件或其它，这里只暴露 Agent 需要的字段。
 */
public record LogEntry(long ts, String type, String target, String payload) {
}
