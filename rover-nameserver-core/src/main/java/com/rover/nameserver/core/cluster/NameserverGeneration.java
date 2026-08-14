package com.rover.nameserver.core.cluster;

/**
 * Nameserver 对外「世代」视图：推送/查询里的 epoch 从这里来。
 */
public interface NameserverGeneration {

    /**
     * 当前权威世代号（协议字段 epoch）。
     * 客户端：同 epoch 内用 revision 拒旧；epoch 变化视为换代并接受。
     */
    String epoch();

    /**
     * 集群任期预留；单机固定 0。
     * 以后可写入 CommonResponseBody / 推送扩展，先留方法避免调用方写死「只有 UUID」。
     */
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
