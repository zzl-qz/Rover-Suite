package com.rover.common.protocol;

import com.rover.common.model.ServiceInstance;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 查询结果
 *
 * 这个类是什么：QUERY_REQUEST 的应答体。
 * 核心职责：返回查询到的实例列表与服务版本号；客户端可用 revision 做增量/缓存判断。
 * 被谁用：注册中心服务端组装返回；客户端解析查询应答。
 */
@Data
public class QueryResponseBody {

    /** 实例列表 */
    private List<ServiceInstance> instances = new ArrayList<>();
    /** 服务版本号 */
    private long revision;
}
