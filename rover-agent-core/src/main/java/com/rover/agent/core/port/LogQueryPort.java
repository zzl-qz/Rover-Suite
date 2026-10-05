package com.rover.agent.core.port;

import java.util.List;

/** 历史日志查询端口，底层存储由宿主适配器提供。 */
public interface LogQueryPort {

    /**
     * 按请求查询历史日志。
     *
     * @throws SnapshotUnavailableException 存储不可用或查询失败时抛出，便于上层统一处理为「证据缺失」。
     */
    List<LogEntry> query(LogRequest request);
}
