package com.rover.agent.core.port;

/**
 * 路由控制端口：受控变更链路<b>唯一</b>允许写 Gateway 的出口。
 *
 * <p>它刻意比「管理口 API 代理」窄得多，只暴露一个动作需要的东西：
 *
 * <ul>
 *   <li>{@link #state()}：读整表 + 版本号（提议取依据、执行前做预检、执行后做回读，全用它）；</li>
 *   <li>{@link #preview}：候选变更的差异预览，不落盘、不生效；</li>
 *   <li>{@link #adjustTargetWeight}：唯一一个写方法，且必须带期望版本与幂等号；</li>
 *   <li>{@link #operation}：按幂等号回查一次写的真实结果。</li>
 * </ul>
 *
 * <p>不做「删除路由」「改配置」「摘实例」——端口上没有这些方法，模型与执行器就不可能调用到它们。
 * 能力边界落在接口上，而不是落在提示词或代码评审里。
 *
 * <p>实现由宿主进程提供（Admin 侧适配器转调管理口），因此运行层不依赖任何具体 HTTP 客户端。
 */
public interface RouteControlPort {

    /**
     * 读取当前路由表与版本号。
     *
     * @throws SnapshotUnavailableException 管理口不可达或返回结构无法解析
     */
    RouteControlState state();

    /**
     * 预览一次权重调整的差异：网关按整表校验并比对，不落盘、不生效。
     *
     * <p>预览与提交是两件事：预览通过不代表提交一定成功（期间别人可能又改了路由，
     * 提交仍会被 revision 拦下）。它的价值是在动手之前，把「网关认不认这个候选表、
     * 会改动什么」摊开给人和程序看。
     *
     * @param routeId     路由标识（id 或业务前缀）
     * @param serviceName 目标服务名
     * @param group       目标版本分组
     * @param weight      候选权重
     * @throws RouteControlException 候选表校验不通过，或管理口不可达
     */
    RouteChangePreview preview(String routeId, String serviceName, String group, int weight);

    /**
     * 调整某个版本目标的权重：唯一一个写方法。
     *
     * <p>参数里的 {@code operationId} 是幂等号：同一个号重复提交，网关只会让第一次生效，
     * 之后返回 {@code REPLAYED}。因此调用方必须在<b>发请求之前</b>把它落库，
     * 否则超时后就再也认不出「那一次」了。
     *
     * @throws RouteControlException 冲突 / 被拒 / 落盘失败 / 没有响应（{@link RouteControlException#outcomeUnknown()}）
     */
    RouteChangeResult adjustTargetWeight(String routeId, String serviceName, String group, int weight,
                                         int expectedRevision, String operationId);

    /**
     * 按幂等号回查一次写操作的结果。
     *
     * <p>这是「超时之后」唯一允许的动作：回查确认生效就继续验证，确认没生效才谈重试。
     *
     * @throws RouteControlException {@link RouteControlException.Kind#UNAVAILABLE}：连回查都拿不到结果
     */
    RouteOperation operation(String operationId);
}
