package com.rover.nameserver.core.model;

import com.rover.common.model.ServiceInstance;
import com.rover.common.protocol.RegisterRequest;
import java.util.HashMap;
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
    /** 最近心跳时间；健康检查线程要读，必须看得见心跳线程的写 */
    private volatile long lastHeartbeatMillis;

    /**
     * 从注册请求构造实例记录：补全服务端默认值（注册时间、健康状态、权重、元数据），
     * 并以后者为「最近心跳时间」起点。
     *
     * @param request 客户端注册请求
     * @return 可直接放入注册表的记录，含完整的 ServiceInstance 与初始心跳时间
     */
    public static InstanceRecord from(RegisterRequest request) {
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
        record.setLastHeartbeatMillis(now);
        return record;
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
}
