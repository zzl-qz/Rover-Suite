package com.rover.agent.core.investigation;

import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.snapshot.TraceSnapshot;
import java.util.List;

/**
 * 一次结论合成所需的只读事实。
 *
 * @param path          被调查的请求路径
 * @param route         命中的路由；没有匹配项时为 null
 * @param routeRead     路由表是否读取成功，用于区分「没有匹配」与「数据不可用」
 * @param discoveryMode 上游发现模式
 * @param instances     实例快照；不可用时为 null
 * @param traces        追踪快照；不可用时为 null
 */
public record FindingsInput(String path, RouteSnapshot route, boolean routeRead, DiscoveryMode discoveryMode,
                            List<InstanceSnapshot> instances, TraceSnapshot traces) { }