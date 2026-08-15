package com.rover.common.protocol;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-07 09:45:00
 * Description: 注册请求：完整描述实例信息（地址、权重、分组等），服务端登记并开始接受心跳
 */
@Data
public class RegisterRequest {

    /** 服务名 */
    private String serviceName;
    /** 实例地址 */
    private String host;
    /** 实例端口 */
    private int port;
    /** 实例 ID */
    private String instanceId;
    /** 注册时间；0 表示服务端填写 */
    private long registerTime;
    /** 权重 */
    private int weight = 100;
    /** 分组 */
    private String group;
    /** 机房/可用区 */
    private String zone;
    /** 临时实例，默认 true */
    private boolean ephemeral = true;
    /** 扩展 KV */
    private Map<String, String> metadata = new HashMap<>();
}
