package com.rover.agent.core.port;

import java.util.List;

/**
 * Gateway 路由表在某一时刻的可写视图：一张整表 + 一个版本号。
 *
 * <p>版本号是乐观锁的期望值：变更提交时必须带上「我读到的是哪一版」，
 * 网关比对不一致就直接 409，绝不覆盖别人在这期间的修改。因此读整表与读版本号必须是<b>同一次</b>往返，
 * 分成两次读就会拿到「版本 17 的路由 + 版本 18 的内容」这种自相矛盾的输入。
 *
 * @param revision           当前路由版本号
 * @param appliedOperationId 产生当前版本的操作 ID；可能为空
 * @param routes             全部路由
 */
public record RouteControlState(int revision, String appliedOperationId, List<RouteControlRoute> routes) {

    public RouteControlState {
        appliedOperationId = appliedOperationId == null ? "" : appliedOperationId.trim();
        routes = routes == null ? List.of() : List.copyOf(routes);
    }
}
