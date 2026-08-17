package com.rover.nameserver.core.registry;

import com.rover.common.model.ServiceInstance;
import com.rover.common.protocol.RegisterRequest;
import com.rover.nameserver.core.model.InstanceRecord;
import com.rover.nameserver.core.registration.RegistrationOwner;
import com.rover.nameserver.core.registration.RegistrationResult;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-02 11:10:00
 * Description: 服务注册表接口，抽象实例生命周期（注册/注销/心跳/查询）与服务 revision 版本号数据面
 */
public interface ServiceRegistry {

    /**
     * 注册或续租实例。owner 相同且实例信息未变化时只刷新心跳；实例信息或 owner 变化时返回新快照。
     */
    RegistrationResult register(RegisterRequest request, RegistrationOwner owner);

    /** 仅当前 owner 可以注销；实例不存在或 owner 不匹配时不修改注册表。 */
    RegistrationResult unregister(
            String serviceName, String instanceId, RegistrationOwner owner);

    /**
     * 刷新实例心跳。
     * 实例不存在或 owner 不匹配时不续租；健康状态恢复时返回新快照。
     */
    RegistrationResult heartbeat(
            String serviceName, String instanceId, RegistrationOwner owner);

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

    /**
     * 移除健康检查扫描到的过期实例。必须同时匹配扫描时的 owner 且心跳仍早于截止时间，
     * 防止旧扫描误删同 ID 的新会话。
     */
    RegistrySnapshot removeExpired(
            String serviceName,
            String instanceId,
            RegistrationOwner expectedOwner,
            long heartbeatDeadlineMillis);
}
