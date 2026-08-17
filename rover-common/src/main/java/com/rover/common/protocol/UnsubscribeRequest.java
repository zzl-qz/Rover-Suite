package com.rover.common.protocol;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-07 10:05:00
 * Description: 取消订阅请求：按服务名（可含分组）解除服务端订阅关系
 */
@Data
public class UnsubscribeRequest {

    /** 服务名 */
    private String serviceName;
    /** 分组，空表示全部解除 */
    private String group;
    /** 集群鉴权 token，服务端开启鉴权时校验；空表示不鉴权 */
    private String token;
}
