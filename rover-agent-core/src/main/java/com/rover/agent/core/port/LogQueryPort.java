package com.rover.agent.core.port;

import java.util.List;

/**
 * 只读端口：Agent 基于历史日志做证据检索（如「上周还好好的现在为什么挂了」）。
 * 领域层只依赖此端口，底层存储（H2 等）由运行/管理侧适配器提供。
 */
public interface LogQueryPort {

    /**
     * 按请求查询历史日志。
     *
     * @throws SnapshotUnavailableException 存储不可用或查询失败时抛出，便于上层统一处理为「证据缺失」。
     */
    List<LogEntry> query(LogRequest request);
}
