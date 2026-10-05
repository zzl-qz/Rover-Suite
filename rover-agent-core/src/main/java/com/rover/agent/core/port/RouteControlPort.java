package com.rover.agent.core.port;

/**
 * 受控路由变更端口，提供状态读取、差异预览、单目标权重调整与操作回查。
 * 写入必须携带期望版本和幂等号，实现由宿主进程提供。
 */
public interface RouteControlPort {

    /**
     * 读取当前路由表与版本号。
     *
     * @throws SnapshotUnavailableException 管理口不可达或返回结构无法解析
     */
    RouteControlState state();

    /**
     * 预览权重调整的整表差异，不提交变更；提交时仍需校验 revision。
     *
     * @param routeId     路由标识（id 或业务前缀）
     * @param serviceName 目标服务名
     * @param group       目标版本分组
     * @param weight      候选权重
     * @throws RouteControlException 候选表校验不通过，或管理口不可达
     */
    RouteChangePreview preview(String routeId, String serviceName, String group, int weight);

    /**
     * 调整单个目标的权重；operationId 必须在提交前落库，重放返回 REPLAYED。
     *
     * @throws RouteControlException 冲突 / 被拒 / 落盘失败 / 没有响应（{@link RouteControlException#outcomeUnknown()}）
     */
    RouteChangeResult adjustTargetWeight(String routeId, String serviceName, String group, int weight,
                                         int expectedRevision, String operationId);

    /**
     * 按原 operationId 确认写操作结果；超时后须先回查再决定是否重试。
     *
     * @throws RouteControlException {@link RouteControlException.Kind#UNAVAILABLE}：连回查都拿不到结果
     */
    RouteOperation operation(String operationId);
}
