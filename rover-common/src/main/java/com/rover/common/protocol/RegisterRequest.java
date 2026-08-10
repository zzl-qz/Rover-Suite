package com.rover.common.protocol;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 注册请求
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
