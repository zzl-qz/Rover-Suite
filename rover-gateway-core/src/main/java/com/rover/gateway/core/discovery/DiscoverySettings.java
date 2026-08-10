package com.rover.gateway.core.discovery;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:20:00
 * Description: Gateway 发现运行时配置
 */
@Data
public class DiscoverySettings {

    private DiscoveryType type = DiscoveryType.STATIC;

    /** Nameserver host，仅 NAMESERVER 模式使用 */
    private String nameserverHost = "127.0.0.1";

    /** Nameserver port */
    private int nameserverPort = 8888;

    /** 定时对账间隔 */
    private long reconcileIntervalMs = 30000L;

    /** 需要订阅的服务；由路由 serviceName 汇总 */
    private List<ServiceSubscribeSpec> subscribeServices = new ArrayList<>();

    @Data
    public static class ServiceSubscribeSpec {
        private String serviceName;
        private String group;
    }
}
