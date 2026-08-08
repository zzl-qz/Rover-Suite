package com.rover.common.protocol;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 注册请求
 */
@Data
public class RegisterRequest {

    private String serviceName;
    private String host;
    private int port;
    private String instanceId;
    private long registerTime;
    private int weight = 100;
    private String group;
    private String zone;

    // 断连后能不能直接摘掉
    private boolean ephemeral = true;

    // 额外信息往这里塞，少改字段
    private Map<String, String> metadata = new HashMap<>();
}
