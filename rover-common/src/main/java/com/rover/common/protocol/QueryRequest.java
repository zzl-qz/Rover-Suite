package com.rover.common.protocol;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 查询实例
 */
@Data
public class QueryRequest {

    private String serviceName;
    private String group;
    private boolean healthyOnly = true;
}
