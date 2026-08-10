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
 * 核心职责：描述一次注册/注销/剔除变更完成后，某个服务在某个分组下的
 * 完整实例状态与版本信息，是注册表与推送/查询之间的标准数据载体。</p>
 *
 * 被 {@link InMemoryServiceRegistry} 在每次变更后生成、被
 * {@link com.rover.nameserver.core.push.PushService} 作为推送体数据源、
 * 被请求分发器在新订阅建立时用「当前全量」构造成初始推送。</p>
 *
 * revision 字段是关键：随每次变更递增，订阅方客户端以此做新旧判断与去重；
 * group 标注变更发生时的分组上下文（通配订阅仍会收到，由客户端侧自行过滤）。</p>
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