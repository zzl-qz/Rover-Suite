package com.rover.common.protocol;

import com.rover.common.model.ServiceInstance;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 服务变更推送
 *
 * 这个类是什么：注册中心主动推送实例变更给订阅者的消息体(PUSH_RESPONSE 帧)。
 * 核心职责：携带服务名、当前全量实例快照与版本号，客户端据此全量刷新本地缓存。
 * 被谁用：注册中心服务端推送链路组装；客户端订阅回调解析刷新。
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
    /**
     * Nameserver 权威世代（协议字段 epoch，不透明字符串）。
     * 单机多为进程 UUID；集群应为共享世代（如 term/配置版本），由 Generation 实现决定。
     */
    private String epoch;
    /** 推送类型，目前固定 SNAPSHOT */
    private String pushType = "SNAPSHOT";
}
