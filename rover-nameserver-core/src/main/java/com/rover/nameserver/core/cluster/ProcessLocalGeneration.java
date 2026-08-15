package com.rover.nameserver.core.cluster;

import java.util.UUID;

/**
 * Author: Daylight
 * Created: 2026-08-05 10:40:00
 * Description: 单机世代实现：进程启动时生成 UUID 世代，仅存于内存，集群场景不可复用为多节点权威源
 */
public final class ProcessLocalGeneration implements NameserverGeneration {

    private final String epoch;

    public ProcessLocalGeneration() {
        this.epoch = UUID.randomUUID().toString();
    }

    /** 测试或显式注入用 */
    public ProcessLocalGeneration(String epoch) {
        if (epoch == null || epoch.isBlank()) {
            throw new IllegalArgumentException("epoch 不能为空");
        }
        this.epoch = epoch;
    }

    @Override
    public String epoch() {
        return epoch;
    }
}
