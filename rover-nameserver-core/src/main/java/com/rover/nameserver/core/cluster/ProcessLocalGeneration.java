package com.rover.nameserver.core.cluster;

import java.util.UUID;

/**
 * 单机世代：进程启动 UUID，只活在内存里。
 * 集群不要复用这个类当多节点权威源。
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
