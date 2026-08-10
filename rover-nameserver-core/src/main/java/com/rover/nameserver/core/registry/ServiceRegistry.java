package com.rover.nameserver.core.registry;

import com.rover.common.model.ServiceInstance;
import com.rover.common.protocol.RegisterRequest;
import com.rover.nameserver.core.model.InstanceRecord;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: 服务注册表
 *
 * 核心职责：服务注册中心的数据面抽象，定义实例生命周期管理
 * （注册/注销/心跳）与查询（按服务、按组、健康过滤）的契约，
 * 并暴露服务版本号与内部记录枚举能力。</p>
 *
 * 被谁用：{@link com.rover.nameserver.core.server.NameserverRequestDispatcher}
 * 处理注册/注销/心跳/查询请求时调用；{@link com.rover.nameserver.core.health.HealthChecker}
 * 扫描与剔除过期实例时调用；push 链路经由 dispatch 间接消费返回值快照。</p>
 *
 * 当前唯一实现为单机内存版 {@link InMemoryServiceRegistry}；
 * 后续集群或多存储实现（如持久化/多副本）实现本接口即可替换，签名约定不变。</p>
 */
public interface ServiceRegistry {

    /**
     * 注册一个实例。
     *
     * @param request 注册请求（服务名/实例 ID/主机/端口/分组等）
     * @return 注册完成后该服务的完整快照（含新 revision），非 null，用于变更推送
     */
    RegistrySnapshot register(RegisterRequest request);

    /**
     * 注销一个实例。
     *
     * @param serviceName 服务名
     * @param instanceId  实例 ID
     * @return 注销完成后该服务的完整快照；实例不存在时返回 null（调用方应跳过推送）
     */
    RegistrySnapshot unregister(String serviceName, String instanceId);

    /**
     * 处理一次心跳，刷新实例的最近心跳时间并恢复健康状态。
     *
     * @param serviceName 服务名
     * @param instanceId  实例 ID
     * @return true=实例存在且已刷新；false=实例不存在（客户端应先注册）
     */
    boolean heartbeat(String serviceName, String instanceId);

    /**
     * 查询服务实例列表。
     *
     * @param serviceName 服务名
     * @param group       组过滤条件；null/空白表示不过滤
     * @param healthyOnly true 时仅返回健康实例
     * @return 匹配实例列表（防御性副本），空表示无匹配
     */
    List<ServiceInstance> query(String serviceName, String group, boolean healthyOnly);

    /**
     * 查询某服务当前版本号（用于回复与推送去重）。
     *
     * @param serviceName 服务名
     * @return 服务最新 revision；未发生过变更时为 0
     */
    long revisionOf(String serviceName);

    /**
     * 枚举注册表内全部实例记录（供健康检查扫描超时实例）。
     *
     * @return 全部内部记录的浅拷贝列表
     */
    List<InstanceRecord> listAllRecords();

    /**
     * 移除过期实例（由健康检查在临时实例超时时调用）。
     *
     * @param serviceName 服务名
     * @param instanceId  实例 ID
     * @return 移除后的服务快照；实例不存在（已被并发移除）时返回 null
     */
    RegistrySnapshot removeExpired(String serviceName, String instanceId);
}