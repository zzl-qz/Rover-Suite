package com.rover.nameserver.core.cluster;

/**
 * Author: Daylight
 * Created: 2026-08-05 09:30:00
 * Description: Nameserver 对外世代视图抽象，推送/查询中的 epoch 由此提供，集群可替换实现
 */
public interface NameserverGeneration {

    /** 当前权威世代号（协议字段 epoch）：客户端同 epoch 内用 revision 拒旧，epoch 变化视为换代并接受。 */
    String epoch();

    /** 集群任期预留；单机固定 0。 */
    default long term() {
        return 0L;
    }

    /** 当前 Leader 提示，单机可空；对应协议 leaderHint。 */
    default String leaderHint() {
        return null;
    }

    /** 单机默认：进程启动时生成本地世代。 */
    static NameserverGeneration processLocal() {
        return new ProcessLocalGeneration();
    }
}
