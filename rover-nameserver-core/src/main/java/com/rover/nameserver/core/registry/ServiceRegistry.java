package com.rover.nameserver.core.registry;

import com.rover.common.model.ServiceInstance;
import com.rover.common.protocol.RegisterRequest;
import com.rover.nameserver.core.model.InstanceRecord;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-02 11:10:00
 * Description: 服务注册表接口，抽象实例生命周期（注册/注销/心跳/查询）与服务 revision 版本号数据面
 */
public interface ServiceRegistry {

    /** 注册实例，返回该服务的完整快照（含新 revision），用于变更推送。 */
    RegistrySnapshot register(RegisterRequest request);

    /** 注销实例，返回新快照；实例不存在时返回 null（调用方应跳过推送）。 */
    RegistrySnapshot unregister(String serviceName, String instanceId);

    /**
     * 刷新实例心跳。
     * 实例不存在 → {@link HeartbeatResult#notFound()}；
     * 仅刷新时间 → {@link HeartbeatResult#touched()}；
     * 从不健康恢复 → {@link HeartbeatResult#recovered(RegistrySnapshot)}（已 bump revision）。
     */
    HeartbeatResult heartbeat(String serviceName, String instanceId);

    /**
     * 将实例标记为不健康。
     * 若状态确有变化则 bump revision 并返回快照；实例不存在或已是不健康则返回 null。
     */
    RegistrySnapshot markUnhealthy(
            String serviceName, String instanceId, long heartbeatDeadlineMillis);

    /** 按服务、组过滤查询实例（healthyOnly 时仅返回健康实例），返回防御性副本。 */
    List<ServiceInstance> query(String serviceName, String group, boolean healthyOnly);

    /** 查询服务当前版本号（用于回复与推送去重）；未发生过变更时为 0。 */
    long revisionOf(String serviceName);

    /** 枚举注册表内全部实例记录（供健康检查扫描超时实例）。 */
    List<InstanceRecord> listAllRecords();

    /** 移除过期实例（由健康检查在临时实例超时时调用），返回移除后的服务快照；实例不存在返回 null。 */
    RegistrySnapshot removeExpired(String serviceName, String instanceId);
}
