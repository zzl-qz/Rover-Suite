package com.rover.agent.core.port;

import java.util.List;

/**
 * 同一次管理口读取的路由整表与 revision，revision 用于乐观锁。
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
