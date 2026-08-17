package com.rover.nameserver.core.model;

import com.rover.common.model.ServiceInstance;
import com.rover.common.protocol.RegisterRequest;
import com.rover.nameserver.core.registration.RegistrationOwner;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: 注册表内部实例记录：绑定对外 ServiceInstance 与心跳时间，提供记录构造与心跳刷新能力
 */
@Data
public class InstanceRecord {

    /** 对外实例信息 */
    private ServiceInstance instance;
    /** 当前注册会话所有者；旧连接/旧 session 不能给新会话续租或注销。 */
    private RegistrationOwner owner;
    /** 最近心跳时间；健康检查线程要读，必须看得见心跳线程的写 */
    private volatile long lastHeartbeatMillis;

    /**
     * 从注册请求构造实例记录：补全服务端默认值（注册时间、健康状态、权重、元数据），
     * 并以后者为「最近心跳时间」起点。
     *
     * @param request 客户端注册请求
     * @return 可直接放入注册表的记录，含完整的 ServiceInstance 与初始心跳时间
     */
    public static InstanceRecord from(RegisterRequest request, RegistrationOwner owner) {
        long now = System.currentTimeMillis();
        ServiceInstance instance = new ServiceInstance();
        instance.setServiceName(request.getServiceName());
        instance.setHost(request.getHost());
        instance.setPort(request.getPort());
        instance.setInstanceId(request.getInstanceId());
        // 客户端可自带注册时间，未带或非法（<=0）时用服务端当前时间兜底
        instance.setRegisterTime(request.getRegisterTime() > 0 ? request.getRegisterTime() : now);
        // 新注册实例默认健康
        instance.setHealthy(true);
        // 权重不合法时默认 100（满权重）
        instance.setWeight(request.getWeight() <= 0 ? 100 : request.getWeight());
        instance.setGroup(request.getGroup());
        instance.setZone(request.getZone());
        instance.setEphemeral(request.isEphemeral());
        // 元数据做防御性拷贝，防止后续外部修改污染请求源对象
        instance.setMetadata(request.getMetadata() == null ? new HashMap<>() : new HashMap<>(request.getMetadata()));

        InstanceRecord record = new InstanceRecord();
        record.setInstance(instance);
        record.setOwner(Objects.requireNonNull(owner, "owner"));
        record.setLastHeartbeatMillis(now);
        return record;
    }

    /** 是否仍由给定连接/session 持有。 */
    public boolean isOwnedBy(RegistrationOwner expectedOwner) {
        return Objects.equals(this.owner, expectedOwner);
    }

    /**
     * 比较会影响服务发现数据面的注册字段。registerTime、token 与健康状态不参与：
     * 它们分别属于首次登记时间、传输鉴权和运行状态，不能让幂等重试产生伪变更。
     */
    public boolean hasSameRegistration(RegisterRequest request) {
        ServiceInstance current = this.instance;
        if (current == null || request == null) {
            return false;
        }
        int requestedWeight = request.getWeight() <= 0
                ? ServiceInstance.DEFAULT_WEIGHT
                : request.getWeight();
        Map<String, String> requestedMetadata =
                request.getMetadata() == null ? Map.of() : request.getMetadata();
        Map<String, String> currentMetadata =
                current.getMetadata() == null ? Map.of() : current.getMetadata();
        return Objects.equals(current.getServiceName(), request.getServiceName())
                && Objects.equals(current.getHost(), request.getHost())
                && current.getPort() == request.getPort()
                && Objects.equals(current.getInstanceId(), request.getInstanceId())
                && current.getWeight() == requestedWeight
                && Objects.equals(current.getGroup(), request.getGroup())
                && Objects.equals(current.getZone(), request.getZone())
                && current.isEphemeral() == request.isEphemeral()
                && Objects.equals(currentMetadata, requestedMetadata);
    }

    /**
     * 刷新心跳时间并恢复健康。
     * @return 此前不健康、这次被拉回健康则为 true，调用方要 bump revision 并推送
     */
    public synchronized boolean touchHeartbeat() {
        this.lastHeartbeatMillis = System.currentTimeMillis();
        if (this.instance == null) {
            return false;
        }
        boolean recovered = !this.instance.isHealthy();
        this.instance.setHealthy(true);
        return recovered;
    }

    /**
     * 仅当最近心跳仍早于截止时间时标记不健康。
     * 与 {@link #touchHeartbeat()} 使用同一把记录锁，避免新心跳插在超时检查与状态翻转之间。
     *
     * @param heartbeatDeadlineMillis 最近心跳必须小于等于此时间才算超时
     * @return 健康状态是否从 true 翻转为 false
     */
    public synchronized boolean markUnhealthyIfExpired(long heartbeatDeadlineMillis) {
        if (instance == null
                || !instance.isHealthy()
                || lastHeartbeatMillis > heartbeatDeadlineMillis) {
            return false;
        }
        instance.setHealthy(false);
        return true;
    }

    /** 健康检查真正删除前再次确认心跳仍已过期，避免扫描快照与删除之间的新心跳被误删。 */
    public synchronized boolean isHeartbeatExpired(long heartbeatDeadlineMillis) {
        return lastHeartbeatMillis <= heartbeatDeadlineMillis;
    }
}
