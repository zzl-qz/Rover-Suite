package com.rover.common.protocol;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:40:00
 * Description: 取消订阅
 */
@Data
public class UnsubscribeRequest {

    private String serviceName;
    private String group;
}
