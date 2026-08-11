package com.rover.nameserver.core.registry;

import com.rover.common.model.ServiceInstance;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: 某次变更后的服务快照
 *
 * 这个类是什么：一次注册/注销/剔除变更完成后的服务状态载体。
 * 核心职责：携带 serviceName、group、revision 与实例全量列表，
 * 供推送与查询之间传递；revision 用于客户端去重。
 * 被谁用：InMemoryServiceRegistry 生成、PushService 推送、RequestDispatcher 订阅初始推送。
 */
@Data
public class RegistrySnapshot {

    /** 服务名 */
    private String serviceName;
    /** 分组 */
    private String group;
    /** 版本号 */
    private long revision;
    /** 当前实例列表 */
    private List<ServiceInstance> instances = new ArrayList<>();

    /**
     * 构造快照（防御性拷贝实例列表，防止外部修改影响快照内容）。
     *
     * @param serviceName 服务名
     * @param group       分组标注
     * @param revision    变更后版本号
     * @param instances   实例列表；null 按空列表处理
     * @return 组装完成的快照
     */
    public static RegistrySnapshot of(
            String serviceName, String group, long revision, List<ServiceInstance> instances) {
        RegistrySnapshot snapshot = new RegistrySnapshot();
        snapshot.setServiceName(serviceName);
        snapshot.setGroup(group);
        snapshot.setRevision(revision);
        snapshot.setInstances(instances == null ? new ArrayList<>() : new ArrayList<>(instances));
        return snapshot;
    }
}