package com.rover.agent.runtime.repository;

/**
 * 内存存储容量已满：写入被拒，没有写入任何记录，也没有淘汰任何旧记录。
 *
 * 这是可见的失败：上层保留策略（会话整组清理、任务终态淘汰）应先腾出位置；
 * 走到这里说明容量配置与该部署的实际负载不符，需要调大容量或缩短保留窗口。
 */
public class StoreCapacityExceededException extends IllegalStateException {

    public StoreCapacityExceededException(String message) {
        super(message);
    }
}