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

    /** 服务名 */
    private String serviceName;
    /** 分组 */
    private String group;
    /** 当前全量实例 */
    private List<ServiceInstance> instances = new ArrayList<>();
    /** 版本号 */
    private long revision;
    /** 推送类型，目前固定 SNAPSHOT */
    private String pushType = "SNAPSHOT";
}
