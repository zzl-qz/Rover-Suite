package com.rover.agent.core.port;

import com.rover.agent.core.snapshot.InstanceSnapshot;
import java.util.List;

/** 只读读取注册中心的实例列表。 */
public interface InstanceReadPort {

    /** 读取当前注册实例快照。不可用时抛 {@link SnapshotUnavailableException}。 */
    List<InstanceSnapshot> instances();
}