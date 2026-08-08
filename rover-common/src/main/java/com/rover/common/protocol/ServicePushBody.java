package com.rover.common.protocol;

import com.rover.common.model.ServiceInstance;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 服务变更推送
 */
@Data
public class ServicePushBody {

    private String serviceName;
    private String group;
    private List<ServiceInstance> instances = new ArrayList<>();
    private long revision;

    // 目前先推全量，增量以后再说
    private String pushType = "SNAPSHOT";
}
