package com.rover.common.protocol;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-07 09:55:00
 * Description: 查询实例请求：按服务名（可附加分组与健康过滤）查询可用实例
 */
@Data
public class QueryRequest {

    /** 服务名 */
    private String serviceName;
    /** 分组过滤，可空 */
    private String group;
    /** 是否只返回健康实例 */
    private boolean healthyOnly = true;
    /** 集群鉴权 token，服务端开启鉴权时校验；空表示不鉴权 */
    private String token;
}
