package com.rover.agent.core.port;

import com.rover.agent.core.snapshot.RegistryEventSnapshot;
import java.util.List;

/** 只读读取注册中心事件（注册、注销、剔除、标记不健康、订阅推送）。 */
public interface EventReadPort {

    /**
     * 读取注册中心最近事件，倒序（最新在前）。
     * 数据不可用时抛 {@link SnapshotUnavailableException}。
     */
    List<RegistryEventSnapshot> events();
}
