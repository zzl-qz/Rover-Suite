package com.rover.common.protocol;

import com.rover.common.model.ServiceInstance;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 查询结果
 */
@Data
public class QueryResponseBody {

    private List<ServiceInstance> instances = new ArrayList<>();

    // 版本号，客户端可以拿去判断有没有变化
    private long revision;
}
