package com.rover.common.protocol;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 查询实例
 */
@Data
public class QueryRequest {

    /** 服务名 */
    private String serviceName;
    /** 分组过滤，可空 */
    private String group;
    /** 是否只返回健康实例 */
    private boolean healthyOnly = true;
}
