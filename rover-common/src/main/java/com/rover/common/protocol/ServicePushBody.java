package com.rover.common.protocol;

import com.rover.common.model.ServiceInstance;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-07 10:25:00
 * Description: 服务变更推送体（PUSH_RESPONSE 帧）：全量实例快照，客户端据此刷新本地缓存
 */
@Data
public class ServicePushBody {

    /** 服务名 */
    private String serviceName;
    /** 分组 */
    private String group;
    /** 当前全量实例 */
    private List<ServiceInstance> instances = new ArrayList<>();
    /** 服务变更版本号（同进程内单调递增） */
    private long revision;
    /** Nameserver 权威世代（不透明字符串；单机多为进程 UUID，集群应为共享世代） */
    private String epoch;
    /** 推送类型，目前固定 SNAPSHOT */
    private String pushType = "SNAPSHOT";
}
