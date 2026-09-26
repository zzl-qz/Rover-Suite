package com.rover.agent.core.port;

import com.rover.agent.core.snapshot.ConfigEntrySnapshot;
import java.util.List;

/** 只读读取 Gateway / Nameserver 当前生效配置。 */
public interface ConfigReadPort {

    /**
     * 读取当前生效配置快照（两个组件合并返回）。
     * 数据不可用时抛 {@link SnapshotUnavailableException}；单个组件读取失败由实现方以占位条目如实标注。
     */
    List<ConfigEntrySnapshot> configs();
}
