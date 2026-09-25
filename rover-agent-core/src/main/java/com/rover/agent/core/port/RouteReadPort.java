package com.rover.agent.core.port;

import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.RouteSnapshot;
import java.util.List;

/** 只读读取 Gateway 路由与上游发现模式。 */
public interface RouteReadPort {

    /** 读取 Gateway 当前路由表快照。不可用时抛 {@link SnapshotUnavailableException}。 */
    List<RouteSnapshot> routes();

    /** 读取 Gateway 当前服务发现模式。读取失败返回 {@link DiscoveryMode#UNKNOWN}，由调用方记录为判断边界。 */
    DiscoveryMode discoveryMode();
}