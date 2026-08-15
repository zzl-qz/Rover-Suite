package com.rover.common.protocol;

import com.rover.common.model.ServiceInstance;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-07 10:20:00
 * Description: QUERY_REQUEST 应答体：实例列表与版本号，客户端可用 revision 做增量/缓存判断
 */
@Data
public class QueryResponseBody {

    /** 实例列表 */
    private List<ServiceInstance> instances = new ArrayList<>();
    /** 服务变更版本号（同进程内单调递增） */
    private long revision;
    /** Nameserver 进程启动世代（UUID），与推送字段同义 */
    private String epoch;
}
